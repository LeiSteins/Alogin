use crate::{
    domain::*,
    platform::{
        http::{Host, Http},
        network::snapshot,
    },
    strings,
};
use base64::Engine;
use zeroize::Zeroizing;

pub struct Campus {
    http: Http,
}
impl Campus {
    pub fn new() -> Result<Self, String> {
        Ok(Self { http: Http::new()? })
    }

    pub fn detect(&self) -> Result<Portal, String> {
        let response = self.http.get(Host::Eportal, false, "/")?;
        if (300..400).contains(&response.status) {
            return Ok(
                if response.location.to_ascii_lowercase().contains("eportal") {
                    Portal::Eportal
                } else {
                    Portal::Wlgn
                },
            );
        }
        if !response.success() {
            return Err(strings::detection_error(response.status));
        }
        Ok(if response.text().to_ascii_lowercase().contains("wlgn") {
            Portal::Wlgn
        } else {
            Portal::Eportal
        })
    }

    pub fn login(
        &self,
        username: &str,
        password: &str,
        network: &Network,
    ) -> Result<LoginResult, String> {
        let portal = self.detect()?;
        // Revalidate after portal discovery, immediately before submitting a credential request.
        if &snapshot() != network {
            return Err(strings::NETWORK_CHANGED.into());
        }
        let path = match portal {
            Portal::Wlgn => Zeroizing::new(format!(
                "/drcom/login?{}",
                query(&[
                    ("callback", "dr1003"),
                    ("DDDDD", username),
                    ("upass", password),
                    ("0MKKey", "123456"),
                    ("R1", "0"),
                    ("R2", ""),
                    ("R3", "0"),
                    ("R6", "0"),
                    ("para", "00"),
                    ("v6ip", ""),
                    ("terminal_type", "1"),
                    ("lang", "zh-cn"),
                    ("jsVersion", "4.1"),
                    ("v", "3050"),
                ])
            )),
            Portal::Eportal => {
                if !usable_ipv4(&network.ipv4) {
                    return Err(strings::NO_NETWORK.into());
                }
                let account = if username.contains('@') {
                    username.to_owned()
                } else {
                    format!("{username}@campus")
                };
                Zeroizing::new(format!(
                    "/eportal/portal/login?{}",
                    query(&[
                        ("callback", "dr1003"),
                        ("login_method", "1"),
                        ("user_account", &account),
                        ("user_password", password),
                        ("wlan_user_ip", &network.ipv4),
                        ("wlan_user_ipv6", ""),
                        ("wlan_user_mac", "000000000000"),
                        ("wlan_ac_ip", ""),
                        ("wlan_ac_name", ""),
                        ("jsVersion", "4.2.1"),
                        ("terminal_type", "1"),
                        ("lang", "zh"),
                        ("v", "9842"),
                    ])
                ))
            }
        };
        let response = self.http.get(
            if portal == Portal::Wlgn {
                Host::Wlgn
            } else {
                Host::Eportal
            },
            portal == Portal::Eportal,
            &path,
        )?;
        if !response.success() {
            return Err(strings::login_http_error(response.status));
        }
        if &snapshot() != network {
            return Err(strings::NETWORK_CHANGED.into());
        }
        Ok(parse_login(&response.text()))
    }

    pub fn status(&self, network: &Network) -> Online {
        let mut logged_out = false;
        let mut unresolved = false;
        for host in [Host::Eportal, Host::Wlgn] {
            let Ok(response) = self.http.get(
                host,
                false,
                if matches!(host, Host::Wlgn) {
                    "/a79.htm"
                } else {
                    "/"
                },
            ) else {
                continue;
            };
            if (300..400).contains(&response.status) && points_to_lgn(&response.location) {
                return Online::Yes;
            }
            if !response.success() {
                continue;
            }
            let text = response.text();
            if page_state(&text) == Online::Yes {
                return Online::Yes;
            }
            if is_dynamic(&text) {
                match self.dynamic_status(host, &text, network) {
                    Online::Yes => return Online::Yes,
                    Online::No => logged_out = true,
                    Online::Unknown => unresolved = true,
                }
            } else if page_state(&text) == Online::No {
                logged_out = true;
            }
        }
        if logged_out && !unresolved {
            Online::No
        } else {
            Online::Unknown
        }
    }

    fn dynamic_status(&self, host: Host, page: &str, network: &Network) -> Online {
        self.dynamic_status_inner(host, page, network)
            .unwrap_or(Online::Unknown)
    }

    fn dynamic_status_inner(&self, host: Host, page: &str, network: &Network) -> Option<Online> {
        let ip = &network.ipv4;
        if !usable_ipv4(ip) {
            return None;
        }
        let vlan = page_variable(page, "vlanid");
        let encoded_ip = base64::engine::general_purpose::STANDARD.encode(ip);
        let config_path = format!(
            "/eportal/portal/page/loadConfig?{}",
            query(&[
                ("callback", "dr1003"),
                ("program_index", ""),
                ("wlan_vlan_id", if vlan.is_empty() { "0" } else { &vlan }),
                ("wlan_user_ip", &encoded_ip),
                ("wlan_user_ipv6", ""),
                ("wlan_user_ssid", ""),
                ("wlan_user_areaid", ""),
                ("wlan_ac_ip", ""),
                ("wlan_ap_mac", ""),
                ("gw_id", ""),
                ("jsVersion", "4.X"),
            ])
        );
        let response = self.http.get(host, true, &config_path).ok()?;
        if !response.success() {
            return None;
        }
        let config = jsonp(&response.text())?;
        let method = &config["data"]["check_online_method"];
        let (api, path) = if method == 0 || method == "0" {
            (
                false,
                "/drcom/chkstatus?callback=dr1003&jsVersion=4.X".to_owned(),
            )
        } else if method == 1 || method == "1" {
            let decimal = u32::from(ip.parse::<std::net::Ipv4Addr>().ok()?).to_string();
            (
                true,
                format!(
                    "/eportal/portal/online_list?{}",
                    query(&[
                        ("user_account", ""),
                        ("user_password", ""),
                        (
                            "wlan_user_mac",
                            &page_variable(page, "ss4").to_ascii_uppercase()
                        ),
                        ("wlan_user_ip", &decimal),
                        ("curr_user_ip", &decimal),
                        ("callback", "dr1003"),
                        ("jsVersion", "4.X"),
                    ])
                ),
            )
        } else {
            return None;
        };
        let response = self.http.get(host, api, &path).ok()?;
        if !response.success() {
            return None;
        }
        let status = jsonp(&response.text())?;
        match &status["result"] {
            value if value == 1 || value == "1" => Some(Online::Yes),
            value if value == 0 || value == "0" => Some(Online::No),
            _ => None,
        }
    }
}

fn points_to_lgn(location: &str) -> bool {
    let location = location.to_ascii_lowercase();
    let Some((scheme, remainder)) = location.split_once("://") else {
        return false;
    };
    if scheme != "http" && scheme != "https" {
        return false;
    }
    let authority = remainder.split(['/', '?', '#']).next().unwrap_or_default();
    let (host, port) = authority.split_once(':').unwrap_or((authority, ""));
    host == "lgn.bjut.edu.cn" && (port.is_empty() || port.parse::<u16>().is_ok())
}

fn is_dynamic(page: &str) -> bool {
    static DYNAMIC: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
        regex::Regex::new(r"\bpage\s*\.\s*run\s*\(|\ba41\.js\b").unwrap()
    });
    DYNAMIC.is_match(page)
}

fn page_state(page: &str) -> Online {
    static TITLE: std::sync::LazyLock<regex::Regex> = std::sync::LazyLock::new(|| {
        regex::Regex::new(r"(?i)<title\b[^>]*>\s*(上网登录页|注销页)\s*</title\s*>").unwrap()
    });
    match TITLE.captures(page).map(|c| c[1].to_owned()).as_deref() {
        Some("注销页") => Online::Yes,
        Some("上网登录页") => Online::No,
        _ => Online::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn redirect_identity_requires_exact_campus_host() {
        assert!(points_to_lgn("https://lgn.bjut.edu.cn/"));
        assert!(!points_to_lgn("https://lgn.bjut.edu.cn.attacker.test/"));
        assert!(!points_to_lgn("https://lgn.bjut.edu.cn@attacker.test/"));
        assert!(!points_to_lgn("https://lgn.bjut.edu.cn:443@attacker.test/"));
    }
    #[test]
    fn page_status_does_not_mistake_body_text_for_title() {
        assert_eq!(page_state("<title> 注销页 </title>"), Online::Yes);
        assert_eq!(
            page_state("<TITLE class='x'>上网登录页</TITLE>"),
            Online::No
        );
        assert_eq!(page_state("<html>注销页</html>"), Online::Unknown);
        assert!(is_dynamic("<script src='a41.js'></script>"));
        assert!(is_dynamic("page . run ( )"));
    }
}
