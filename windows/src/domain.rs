use regex::Regex;
use serde::{Deserialize, Serialize};
use std::{net::Ipv4Addr, sync::LazyLock};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Portal {
    Wlgn,
    Eportal,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Online {
    Yes,
    No,
    Unknown,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HomeAction {
    Login,
    Refresh,
}

impl HomeAction {
    pub fn for_session(online: Online) -> Self {
        if online == Online::Yes {
            Self::Refresh
        } else {
            Self::Login
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct AccountInfo {
    pub username: Option<String>,
    pub overview: Option<AccountOverview>,
    pub error: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct AccountOverview {
    pub used_megabytes: Option<f64>,
    pub remaining_megabytes: Option<f64>,
    pub money_yuan: Option<f64>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum LoginResult {
    Success,
    AlreadyOnline,
    Failure(String),
    Unknown,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Network {
    pub interface: u32,
    pub ipv4: String,
    pub ssid: Option<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Preferences {
    pub target_ssids: Vec<String>,
    pub auto_login: bool,
}

impl Preferences {
    pub fn allows_auto_login(&self, network: &Network) -> bool {
        self.auto_login
            && usable_ipv4(&network.ipv4)
            && network
                .ssid
                .as_ref()
                .is_some_and(|ssid| self.target_ssids.contains(ssid))
    }
}

pub fn usable_ipv4(value: &str) -> bool {
    value.parse::<Ipv4Addr>().is_ok_and(|ip| {
        !ip.is_unspecified()
            && !ip.is_loopback()
            && !ip.is_link_local()
            && !ip.is_multicast()
            && !ip.is_broadcast()
    })
}

pub fn encode(value: &str) -> String {
    let mut encoded = String::new();
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~".contains(&byte) {
            encoded.push(byte as char);
        } else {
            use std::fmt::Write;
            write!(encoded, "%{byte:02X}").unwrap();
        }
    }
    encoded
}

pub fn query(params: &[(&str, &str)]) -> String {
    params
        .iter()
        .map(|(key, value)| format!("{}={}", encode(key), encode(value)))
        .collect::<Vec<_>>()
        .join("&")
}

pub fn parse_login(text: &str) -> LoginResult {
    static RESULT: LazyLock<Regex> =
        LazyLock::new(|| Regex::new(r#"(?i)["']?result["']?\s*:\s*["']?(\d+)"#).unwrap());
    static MESSAGE: LazyLock<Regex> = LazyLock::new(|| {
        Regex::new(r#"(?i)["']?(?:msga|msg|message)["']?\s*:\s*["']([^"']+)["']"#).unwrap()
    });
    static ONLINE: LazyLock<Regex> = LazyLock::new(|| {
        Regex::new(r"(?i)\bIP\s*[:：]?\s*(?:(?:\d{1,3}\.){3}\d{1,3}\s*)?(?:已经|已)\s*在线")
            .unwrap()
    });
    let normalized = text.replace("\\\"", "\"");
    let message = MESSAGE
        .captures(&normalized)
        .map(|c| c[1].trim().to_owned());
    let already_online = ONLINE.is_match(message.as_deref().unwrap_or(&normalized));
    let result = RESULT.captures(&normalized).map(|c| c[1].to_owned());
    match result.as_deref() {
        Some("1") => LoginResult::Success,
        _ if already_online => LoginResult::AlreadyOnline,
        Some("0") => LoginResult::Failure(message.unwrap_or_default()),
        _ if normalized.contains("认证成功") || normalized.contains("登录成功") => {
            LoginResult::Success
        }
        _ if normalized.contains("认证失败") || normalized.contains("登录失败") => {
            LoginResult::Failure(message.unwrap_or_default())
        }
        _ => LoginResult::Unknown,
    }
}

pub fn jsonp(text: &str) -> Option<serde_json::Value> {
    let start = text.find('{')?;
    let end = text.rfind('}')?;
    if start > end {
        return None;
    }
    serde_json::from_str(&text[start..=end]).ok()
}

pub fn page_variable(page: &str, name: &str) -> String {
    Regex::new(&format!(
        r#"\b{}\s*=\s*["']([^"']*)["']"#,
        regex::escape(name)
    ))
    .unwrap()
    .captures(page)
    .map(|c| c[1].trim().to_owned())
    .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn home_action_allows_manual_login_when_offline_or_unknown() {
        for online in [Online::No, Online::Unknown] {
            assert_eq!(HomeAction::for_session(online), HomeAction::Login);
        }
    }

    #[test]
    fn home_action_refreshes_an_authenticated_session() {
        assert_eq!(HomeAction::for_session(Online::Yes), HomeAction::Refresh);
    }

    #[test]
    fn login_response_preserves_failure_and_distinguishes_account_from_ip() {
        assert_eq!(
            parse_login(r#"dr1003({"result":1});"#),
            LoginResult::Success
        );
        assert_eq!(
            parse_login(r#"dr1003({"result":0,"msg":"IP: 10.1.2.3 已经在线!"});"#),
            LoginResult::AlreadyOnline
        );
        assert_eq!(
            parse_login(r#"{"result":0,"msg":"账号已经在线，无法登录"}"#),
            LoginResult::Failure("账号已经在线，无法登录".into())
        );
        assert_eq!(
            parse_login(r#"{"result":0,"msg":1,"msga":"Rad:ldap auth error"}"#),
            LoginResult::Failure("Rad:ldap auth error".into())
        );
        assert_eq!(
            parse_login(r#"{"result":0,"msg":"密码错误","ret_code":2}"#),
            LoginResult::Failure("密码错误".into())
        );
        assert_eq!(parse_login("something else"), LoginResult::Unknown);
    }

    #[test]
    fn credentials_are_encoded_as_single_query_values() {
        assert_eq!(encode("a&b=+ #中文"), "a%26b%3D%2B%20%23%E4%B8%AD%E6%96%87");
        assert_eq!(query(&[("user", "a&password=b")]), "user=a%26password%3Db");
    }

    #[test]
    fn automatic_login_requires_explicit_exact_ssid_and_usable_address() {
        let mut settings = Preferences {
            target_ssids: vec!["bjut".into()],
            auto_login: false,
        };
        let mut network = Network {
            interface: 1,
            ipv4: "10.1.2.3".into(),
            ssid: Some("bjut".into()),
        };
        assert!(!settings.allows_auto_login(&network));
        settings.auto_login = true;
        assert!(settings.allows_auto_login(&network));
        network.ssid = Some("bjut-other".into());
        assert!(!settings.allows_auto_login(&network));
        network.ssid = None;
        assert!(!settings.allows_auto_login(&network));
        for address in [
            "0.0.0.0",
            "127.0.0.1",
            "169.254.1.2",
            "999.1.2.3",
            "224.0.0.1",
        ] {
            assert!(!usable_ipv4(address));
        }
    }

    #[test]
    fn extracts_jsonp_and_quoted_page_variables() {
        assert_eq!(jsonp("dr1003({\"result\":1});").unwrap()["result"], 1);
        assert!(jsonp("<html>error</html>").is_none());
        assert!(jsonp("} malformed {").is_none());
        assert_eq!(page_variable("var ss4 = 'aabbcc';", "ss4"), "aabbcc");
    }
}
