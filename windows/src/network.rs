use crate::{
    domain::*,
    platform::{
        http::{Host, Http, Response},
        network::snapshot,
    },
    strings,
};
use base64::Engine;
use zeroize::Zeroizing;

trait Transport {
    fn get(&self, host: Host, api: bool, path: &str) -> Result<Response, String>;
}
impl Transport for Http {
    fn get(&self, host: Host, api: bool, path: &str) -> Result<Response, String> {
        self.get(host, api, path)
    }
}

pub struct Campus {
    http: Http,
}
impl Campus {
    pub fn new() -> Result<Self, String> {
        Ok(Self { http: Http::new()? })
    }

    pub fn login(
        &self,
        username: &str,
        password: &str,
        network: &Network,
    ) -> Result<LoginResult, String> {
        login_with(&self.http, username, password, &|| snapshot() == *network)
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

fn detect(client: &impl Transport) -> Result<Portal, String> {
    let response = client.get(Host::Eportal, false, "/")?;
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

fn login_with(
    client: &impl Transport,
    username: &str,
    password: &str,
    current: &impl Fn() -> bool,
) -> Result<LoginResult, String> {
    require_current(current)?;
    let portal = detect(client)?;
    // Discovery and IP lookup must belong to the same physical network snapshot.
    require_current(current)?;
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
            let ip = fetch_eportal_ipv4(client, current)?;
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
                    ("wlan_user_ip", &ip),
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
    require_current(current)?;
    let response = client.get(
        if portal == Portal::Wlgn {
            Host::Wlgn
        } else {
            Host::Eportal
        },
        portal == Portal::Eportal,
        &path,
    )?;
    require_current(current)?;
    if !response.success() {
        return Err(strings::login_http_error(response.status));
    }
    Ok(parse_login(&response.text()))
}

fn require_current(current: &impl Fn() -> bool) -> Result<(), String> {
    if current() {
        Ok(())
    } else {
        Err(strings::NETWORK_CHANGED.into())
    }
}

fn fetch_eportal_ipv4(
    client: &impl Transport,
    current: &impl Fn() -> bool,
) -> Result<String, String> {
    for path in ["/drcom/chkstatus?callback=dr1003", "/"] {
        require_current(current)?;
        let response = client.get(Host::Eportal, false, path);
        require_current(current)?;
        if let Ok(response) = response {
            if !response.success() {
                continue;
            }
            let address = if path == "/" {
                portal_page_ipv4(&response.text())
            } else {
                portal_status_ipv4(&response.text())
            };
            if let Some(ip) = address {
                return Ok(ip);
            }
        }
    }
    Err(strings::PORTAL_IP_UNAVAILABLE.into())
}

fn portal_page_ipv4(page: &str) -> Option<String> {
    ["v46ip", "ss5", "v4ip"].into_iter().find_map(|field| {
        let ip = page_variable(page, field);
        usable_ipv4(&ip).then_some(ip)
    })
}

fn portal_status_ipv4(body: &str) -> Option<String> {
    // The kernel result can be 0 even when RADIUS reports online; only read IP fields here.
    let status = jsonp(body)?;
    ["v46ip", "ss5", "v4ip"].into_iter().find_map(|field| {
        let ip = status[field].as_str()?.trim();
        usable_ipv4(ip).then(|| ip.to_owned())
    })
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
    use std::{
        cell::{Cell, RefCell},
        collections::VecDeque,
    };

    type RecordedRequest = (Host, bool, String);
    struct Fake {
        responses: RefCell<VecDeque<Result<Response, String>>>,
        requests: RefCell<Vec<RecordedRequest>>,
    }
    impl Transport for Fake {
        fn get(&self, host: Host, api: bool, path: &str) -> Result<Response, String> {
            self.requests.borrow_mut().push((host, api, path.into()));
            self.responses
                .borrow_mut()
                .pop_front()
                .expect("Unexpected request")
        }
    }
    fn fake(responses: Vec<Result<Response, String>>) -> Fake {
        Fake {
            responses: RefCell::new(responses.into()),
            requests: RefCell::new(Vec::new()),
        }
    }
    fn response(status: u32, body: &str) -> Result<Response, String> {
        Ok(Response {
            status,
            body: body.as_bytes().to_vec(),
            location: String::new(),
        })
    }

    #[test]
    fn portal_address_uses_first_valid_server_field_and_trims_padding() {
        for (body, expected) in [
            ("v46ip=' 10.1.2.3 ';ss5='10.4.5.6';", "10.1.2.3"),
            ("v46ip='::1';ss5=' 10.4.5.6 ';", "10.4.5.6"),
            ("ss5='';v4ip='10.7.8.9';", "10.7.8.9"),
        ] {
            assert_eq!(portal_page_ipv4(body).as_deref(), Some(expected));
        }
        assert_eq!(
            portal_status_ipv4(r#"dr1003({"result":0,"v46ip":" 10.1.2.3 ","ss5":"10.4.5.6"});"#)
                .as_deref(),
            Some("10.1.2.3")
        );
        assert_eq!(
            portal_status_ipv4(r#"{"v46ip":123,"ss5":"10.4.5.6"}"#).as_deref(),
            Some("10.4.5.6")
        );
        assert_eq!(
            portal_status_ipv4(r#"{"v46ip":"invalid","v4ip":"10.7.8.9"}"#).as_deref(),
            Some("10.7.8.9")
        );
    }

    #[test]
    fn portal_address_rejects_malformed_and_unusable_addresses() {
        for ip in [
            "",
            "0.0.0.0",
            "127.0.0.1",
            "169.254.1.2",
            "224.0.0.1",
            "255.255.255.255",
            "10.1.2.999",
            "10.1.2",
            "10.1.2.3&x=1",
            "10.1.2.3/24",
            "010.1.2.3",
            "+10.1.2.3",
            "::1",
        ] {
            assert_eq!(portal_page_ipv4(&format!("v46ip='{ip}';")), None, "{ip}");
            assert_eq!(
                portal_status_ipv4(&format!(r#"{{"v46ip":"{ip}"}}"#)),
                None,
                "{ip}"
            );
        }
        for body in [
            "",
            "<html>error</html>",
            "dr1003({",
            "{}",
            r#"{"v46ip":true}"#,
        ] {
            assert_eq!(portal_status_ipv4(body), None);
        }
    }

    #[test]
    fn eportal_login_uses_fresh_server_ip_even_when_kernel_result_is_offline() {
        let client = fake(vec![
            response(200, "v46ip='10.9.8.7';"),
            response(200, r#"dr1003({"result":0,"v46ip":"10.1.2.3"});"#),
            response(200, r#"{"result":1}"#),
        ]);
        assert_eq!(
            login_with(&client, "student", "test-password", &|| true),
            Ok(LoginResult::Success)
        );
        let requests = client.requests.borrow();
        assert_eq!(requests.len(), 3);
        assert_eq!(
            requests[1],
            (
                Host::Eportal,
                false,
                "/drcom/chkstatus?callback=dr1003".into()
            )
        );
        assert!(requests[2].1);
        assert!(requests[2].2.contains("wlan_user_ip=10.1.2.3&"));
        assert!(!requests[2].2.contains("10.9.8.7"));
        assert!(requests[2].2.contains("user_account=student%40campus&"));
        assert!(requests.iter().all(|r| r.0 == Host::Eportal));
        assert!(!requests[1].2.contains("user_password"));
    }

    #[test]
    fn eportal_login_falls_back_to_homepage_on_status_failure_or_redirect() {
        for failure in [
            Err("unavailable".into()),
            response(503, "error"),
            response(200, r#"{"v46ip":"0.0.0.0"}"#),
            Ok(Response {
                status: 302,
                body: Vec::new(),
                location: "https://untrusted.example/".into(),
            }),
        ] {
            let client = fake(vec![
                response(200, "eportal"),
                failure,
                response(200, "v46ip='';ss5=' 10.4.5.6 ';"),
                response(200, r#"{"result":1}"#),
            ]);
            assert_eq!(
                login_with(&client, "student", "test-password", &|| true),
                Ok(LoginResult::Success)
            );
            let requests = client.requests.borrow();
            assert_eq!(requests.len(), 4);
            assert_eq!(requests[2], (Host::Eportal, false, "/".into()));
            assert!(requests[3].2.contains("wlan_user_ip=10.4.5.6&"));
            assert!(requests.iter().all(|r| r.0 == Host::Eportal));
        }
    }

    #[test]
    fn eportal_login_missing_server_ip_never_submits_credentials() {
        let client = fake(vec![
            response(200, "eportal"),
            response(200, "{}"),
            response(200, "no IP"),
        ]);
        assert_eq!(
            login_with(&client, "student", "test-password", &|| true),
            Err(strings::PORTAL_IP_UNAVAILABLE.into())
        );
        let requests = client.requests.borrow();
        assert_eq!(requests.len(), 3);
        assert!(requests.iter().all(|r| !r.2.contains("test-password")));
    }

    #[test]
    fn login_rechecks_network_after_discovery_ip_lookup_and_before_credentials() {
        // Checks: before/after discovery, before/after IP lookup, before submission.
        for allowed_checks in [0, 1, 3, 4] {
            let client = fake(vec![
                response(200, "eportal"),
                response(200, r#"{"v46ip":"10.1.2.3"}"#),
            ]);
            let checks = Cell::new(0);
            let result = login_with(&client, "student", "test-password", &|| {
                let count = checks.get();
                checks.set(count + 1);
                count < allowed_checks
            });
            assert_eq!(result, Err(strings::NETWORK_CHANGED.into()));
            assert!(
                client
                    .requests
                    .borrow()
                    .iter()
                    .all(|r| !r.2.contains("user_password"))
            );
        }
    }

    #[test]
    fn login_network_change_during_submission_discards_successful_response() {
        let client = fake(vec![
            response(200, "eportal"),
            response(200, r#"{"v46ip":"10.1.2.3"}"#),
            response(200, r#"{"result":1}"#),
        ]);
        let result = login_with(&client, "student", "test-password", &|| {
            client.requests.borrow().len() < 3
        });
        assert_eq!(result, Err(strings::NETWORK_CHANGED.into()));
    }

    #[test]
    fn wlgn_login_does_not_request_or_submit_an_ip() {
        let client = fake(vec![
            response(200, "wlgn"),
            response(200, r#"{"result":1}"#),
        ]);
        assert_eq!(
            login_with(&client, "student", "test-password", &|| true),
            Ok(LoginResult::Success)
        );
        let requests = client.requests.borrow();
        assert_eq!(requests.len(), 2);
        assert_eq!((requests[1].0, requests[1].1), (Host::Wlgn, false));
        assert!(requests[1].2.starts_with("/drcom/login?"));
        assert!(!requests[1].2.contains("wlan_user_ip"));
    }
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
