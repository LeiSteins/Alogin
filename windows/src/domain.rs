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
    pub fn for_session(online: Online, network: &Network, preferences: &Preferences) -> Self {
        if online == Online::No
            && usable_ipv4(&network.ipv4)
            && (network.kind == NetworkKind::Ethernet || preferences.is_target_wifi(network))
        {
            Self::Login
        } else {
            Self::Refresh
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct AccountInfo {
    pub username: Option<String>,
    pub overview: Option<AccountOverview>,
    pub error: Option<String>,
    pub devices: Option<Vec<AccountDevice>>,
    pub devices_error: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AccountDevice {
    pub session_id: String,
    pub ip: String,
    pub mac: String,
}

#[derive(Clone, Debug)]
pub struct DeviceLogoutRequest {
    pub generation: u64,
    pub network: Network,
    pub username: String,
    pub device: AccountDevice,
}

impl DeviceLogoutRequest {
    pub fn matches(
        &self,
        generation: u64,
        network: &Network,
        online: Online,
        info: &AccountInfo,
    ) -> bool {
        self.generation == generation
            && self.network == *network
            && online == Online::Yes
            && info.username.as_ref() == Some(&self.username)
            && info
                .devices
                .as_ref()
                .is_some_and(|devices| devices.contains(&self.device))
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct AccountOverview {
    pub used_megabytes: Option<f64>,
    pub remaining_megabytes: Option<f64>,
    pub money_yuan: Option<f64>,
}

impl AccountOverview {
    pub fn traffic_usage_fraction(&self) -> Option<f32> {
        let used = self.used_megabytes?;
        let remaining = self.remaining_megabytes?;
        let total = used + remaining;
        (used.is_finite()
            && remaining.is_finite()
            && used >= 0.0
            && remaining >= 0.0
            && total.is_finite()
            && total > 0.0)
            .then(|| (used / total) as f32)
    }
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
    pub kind: NetworkKind,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum NetworkKind {
    #[default]
    Unknown,
    Wifi,
    Ethernet,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Appearance {
    #[default]
    System,
    Light,
    Dark,
}

impl Appearance {
    pub fn from_index(index: i32) -> Option<Self> {
        match index {
            0 => Some(Self::System),
            1 => Some(Self::Light),
            2 => Some(Self::Dark),
            _ => None,
        }
    }

    pub fn index(self) -> i32 {
        match self {
            Self::System => 0,
            Self::Light => 1,
            Self::Dark => 2,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Preferences {
    pub target_ssids: Vec<String>,
    pub auto_login: bool,
    #[serde(default)]
    pub appearance: Appearance,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            target_ssids: vec!["bjut_wifi".into()],
            auto_login: false,
            appearance: Appearance::System,
        }
    }
}

impl Preferences {
    pub fn is_target_wifi(&self, network: &Network) -> bool {
        network.kind == NetworkKind::Wifi
            && network
                .ssid
                .as_ref()
                .is_some_and(|ssid| self.target_ssids.contains(ssid))
    }

    pub fn add_detected_wifi(&mut self, network: &Network) -> bool {
        let Some(ssid) = network.ssid.as_deref().map(str::trim) else {
            return false;
        };
        if network.kind != NetworkKind::Wifi
            || !ssid.starts_with("bjut-sushe-")
            || self.target_ssids.iter().any(|target| target == ssid)
        {
            return false;
        }
        self.target_ssids.push(ssid.to_owned());
        true
    }

    pub fn allows_auto_login(&self, network: &Network) -> bool {
        self.auto_login && usable_ipv4(&network.ipv4) && self.is_target_wifi(network)
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
    fn logout_admission_requires_same_generation_identity_network_and_exact_device() {
        let device = AccountDevice {
            session_id: "s".into(),
            ip: "10.1.2.4".into(),
            mac: "AA:BB:CC:DD:EE:FF".into(),
        };
        let network = Network {
            ipv4: "10.1.2.3".into(),
            kind: NetworkKind::Ethernet,
            ..Default::default()
        };
        let info = AccountInfo {
            username: Some("student".into()),
            devices: Some(vec![device.clone()]),
            ..Default::default()
        };
        let request = DeviceLogoutRequest {
            generation: 1,
            network: network.clone(),
            username: "student".into(),
            device,
        };
        assert!(request.matches(1, &network, Online::Yes, &info));
        assert!(!request.matches(2, &network, Online::Yes, &info));
        assert!(!request.matches(1, &network, Online::Unknown, &info));
        assert!(!request.matches(
            1,
            &Network {
                kind: NetworkKind::Wifi,
                ..network.clone()
            },
            Online::Yes,
            &info
        ));
        assert!(!request.matches(
            1,
            &network,
            Online::Yes,
            &AccountInfo {
                username: Some("other".into()),
                ..info.clone()
            }
        ));
        let mut changed = info.clone();
        changed.devices.as_mut().unwrap()[0].ip = "10.1.2.5".into();
        assert!(!request.matches(1, &network, Online::Yes, &changed));
        assert!(!request.matches(
            1,
            &network,
            Online::Yes,
            &AccountInfo {
                devices: None,
                ..info
            }
        ));
    }

    #[test]
    fn traffic_usage_requires_known_nonnegative_values_and_a_positive_finite_total() {
        let usage = |used, remaining| {
            AccountOverview {
                used_megabytes: used,
                remaining_megabytes: remaining,
                money_yuan: None,
            }
            .traffic_usage_fraction()
        };
        assert_eq!(usage(Some(3072.0), Some(1024.0)), Some(0.75));
        assert_eq!(usage(Some(0.0), Some(1024.0)), Some(0.0));
        assert_eq!(usage(Some(1024.0), Some(0.0)), Some(1.0));
        for (used, remaining) in [
            (None, Some(1024.0)),
            (Some(1024.0), None),
            (Some(0.0), Some(0.0)),
            (Some(-1.0), Some(1024.0)),
            (Some(1024.0), Some(-1.0)),
            (Some(f64::NAN), Some(1024.0)),
            (Some(1024.0), Some(f64::INFINITY)),
            (Some(f64::MAX), Some(f64::MAX)),
        ] {
            assert_eq!(usage(used, remaining), None);
        }
    }

    #[test]
    fn home_action_requires_confirmed_offline_target_wifi_or_ethernet() {
        let preferences = Preferences::default();
        for (kind, ssid, eligible) in [
            (NetworkKind::Wifi, Some("bjut_wifi"), true),
            (NetworkKind::Wifi, Some("other"), false),
            (NetworkKind::Wifi, None, false),
            (NetworkKind::Ethernet, None, true),
            (NetworkKind::Unknown, None, false),
        ] {
            let network = Network {
                interface: 1,
                ipv4: "10.1.2.3".into(),
                ssid: ssid.map(str::to_owned),
                kind,
            };
            for online in [Online::Yes, Online::No, Online::Unknown] {
                assert_eq!(
                    HomeAction::for_session(online, &network, &preferences),
                    if eligible && online == Online::No {
                        HomeAction::Login
                    } else {
                        HomeAction::Refresh
                    },
                    "{kind:?} {ssid:?} {online:?}"
                );
            }
            let disconnected = Network {
                ipv4: String::new(),
                ..network
            };
            assert_eq!(
                HomeAction::for_session(Online::No, &disconnected, &preferences),
                HomeAction::Refresh
            );
        }
    }

    #[test]
    fn home_action_uses_current_target_configuration_without_limiting_ethernet() {
        let mut preferences = Preferences::default();
        let mut network = Network {
            ipv4: "10.1.2.3".into(),
            ssid: Some("custom".into()),
            kind: NetworkKind::Wifi,
            ..Network::default()
        };
        preferences.target_ssids = vec!["custom".into()];
        assert_eq!(
            HomeAction::for_session(Online::No, &network, &preferences),
            HomeAction::Login
        );
        preferences.target_ssids.clear();
        assert_eq!(
            HomeAction::for_session(Online::No, &network, &preferences),
            HomeAction::Refresh
        );
        network.kind = NetworkKind::Ethernet;
        network.ssid = None;
        assert_eq!(
            HomeAction::for_session(Online::No, &network, &preferences),
            HomeAction::Login
        );
    }

    #[test]
    fn defaults_and_detected_dormitory_wifi_match_android() {
        let mut preferences = Preferences::default();
        assert_eq!(preferences.target_ssids, ["bjut_wifi"]);
        assert!(!preferences.auto_login);
        let mut network = Network {
            ssid: Some("bjut-sushe-101".into()),
            kind: NetworkKind::Wifi,
            ipv4: "10.1.2.3".into(),
            ..Network::default()
        };
        assert!(preferences.add_detected_wifi(&network));
        assert!(!preferences.add_detected_wifi(&network));
        assert_eq!(preferences.target_ssids, ["bjut_wifi", "bjut-sushe-101"]);
        assert_eq!(
            HomeAction::for_session(Online::No, &network, &preferences),
            HomeAction::Login
        );
        assert!(!preferences.allows_auto_login(&network));
        preferences.auto_login = true;
        assert!(preferences.allows_auto_login(&network));
        for ssid in [
            None,
            Some("sushe"),
            Some("other-bjut-sushe-101"),
            Some("BJUT-sushe-101"),
        ] {
            network.ssid = ssid.map(str::to_owned);
            assert!(!preferences.add_detected_wifi(&network));
        }
        network.ssid = Some("bjut-sushe-102".into());
        network.kind = NetworkKind::Unknown;
        assert!(!preferences.add_detected_wifi(&network));
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
            ..Preferences::default()
        };
        let mut network = Network {
            interface: 1,
            ipv4: "10.1.2.3".into(),
            ssid: Some("bjut".into()),
            kind: NetworkKind::Wifi,
        };
        assert!(!settings.allows_auto_login(&network));
        settings.auto_login = true;
        assert!(settings.allows_auto_login(&network));
        network.ssid = Some("bjut-other".into());
        assert!(!settings.allows_auto_login(&network));
        network.ssid = None;
        assert!(!settings.allows_auto_login(&network));
        network.kind = NetworkKind::Ethernet;
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
