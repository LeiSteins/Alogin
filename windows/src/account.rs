//! Account lookup and device operations scoped to a verified identity and network.
use crate::{
    domain::{AccountDevice, AccountInfo, AccountOverview, Network, page_variable, query},
    platform::http::{Host, Http, Response},
    strings,
};
use serde_json::Value;

trait Transport {
    fn get(&self, host: Host, api: bool, path: &str) -> Result<Response, String>;
}
impl Transport for Http {
    fn get(&self, host: Host, api: bool, path: &str) -> Result<Response, String> {
        self.get(host, api, path)
    }
}

pub struct DeviceSession {
    http: Http,
    network: Network,
    username: String,
    devices: Vec<AccountDevice>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum LogoutResult {
    Success,
    Failure,
    Indeterminate,
}

impl DeviceSession {
    pub fn logout(&self, device: &AccountDevice) -> Result<LogoutResult, String> {
        // Idle notifications with an unchanged snapshot do not expire this
        // cookie session. Guard the entire write against new notifications.
        let revision = crate::platform::network::revision();
        logout_with(&self.http, &self.username, &self.devices, device, &|| {
            crate::platform::network::revision() == revision
                && crate::platform::network::snapshot() == self.network
        })
    }
}

pub fn load(network: &Network) -> (AccountInfo, Option<DeviceSession>) {
    let revision = crate::platform::network::revision();
    match Http::new() {
        Ok(http) => {
            let info = load_with(&http, network, &|| {
                crate::platform::network::revision() == revision
                    && crate::platform::network::snapshot() == *network
            });
            let session =
                info.username
                    .as_ref()
                    .zip(info.devices.as_ref())
                    .map(|(username, devices)| DeviceSession {
                        http,
                        network: network.clone(),
                        username: username.clone(),
                        devices: devices.clone(),
                    });
            (info, session)
        }
        Err(_) => (
            AccountInfo {
                error: Some(strings::IDENTITY_FAILED.into()),
                ..Default::default()
            },
            None,
        ),
    }
}

fn load_with(
    client: &impl Transport,
    network: &Network,
    current: &impl Fn() -> bool,
) -> AccountInfo {
    let result = (|| {
        let response = get_current(client, current, Host::Lgn, false, "/")?;
        if !response.success() {
            return Err(strings::IDENTITY_FAILED.into());
        }
        let username = parse_identity(&response.text()).ok_or(strings::IDENTITY_FAILED)?;
        Ok::<_, String>(username)
    })();
    let username = match result {
        Ok(username) => username,
        Err(_) => {
            if !current() {
                return AccountInfo::default();
            }
            return AccountInfo {
                error: Some(strings::IDENTITY_FAILED.into()),
                ..Default::default()
            };
        }
    };
    let result = load_overview(client, network, &username, current);
    // Nothing from an obsolete network may be published, including the identity.
    if !current() {
        return AccountInfo::default();
    }
    match result {
        Ok(overview) => {
            let devices = get_current(
                client,
                current,
                Host::SelfService,
                false,
                "/Self/dashboard/getOnlineList",
            )
            .and_then(|response| {
                if !response.success() {
                    return Err(strings::ACCOUNT_EXPIRED.into());
                }
                parse_devices(&response.text()).map_err(str::to_owned)
            });
            if !current() {
                return AccountInfo::default();
            }
            let (devices, devices_error) = match devices {
                Ok(devices) => (Some(devices), None),
                Err(_) => (None, Some(strings::DEVICES_FAILED.into())),
            };
            AccountInfo {
                username: Some(username),
                overview: Some(overview),
                error: None,
                devices,
                devices_error,
            }
        }
        Err(error) => AccountInfo {
            username: Some(username),
            overview: None,
            error: Some(error),
            ..Default::default()
        },
    }
}

fn canonical_mac(value: &str) -> Option<String> {
    if !value
        .bytes()
        .all(|ch| ch.is_ascii_hexdigit() || b":-. ".contains(&ch))
    {
        return None;
    }
    let mac: String = value.chars().filter(char::is_ascii_hexdigit).collect();
    (mac.len() == 12).then(|| mac.to_ascii_uppercase())
}

fn parse_devices(text: &str) -> Result<Vec<AccountDevice>, &'static str> {
    let data: Value = serde_json::from_str(text).map_err(|_| strings::DEVICES_FAILED)?;
    let rows = data.as_array().ok_or(strings::DEVICES_FAILED)?;
    let mut devices = Vec::new();
    for row in rows {
        let field = |key| {
            row.get(key)
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|value| !value.is_empty() && !value.chars().any(char::is_control))
                .ok_or(strings::DEVICES_FAILED)
        };
        let session_id = field("sessionId")?.to_owned();
        let ip = field("ip")?.to_owned();
        let mac = canonical_mac(field("mac")?).ok_or(strings::DEVICES_FAILED)?;
        if devices
            .iter()
            .any(|device: &AccountDevice| device.session_id == session_id)
        {
            return Err(strings::DEVICES_FAILED);
        }
        devices.push(AccountDevice {
            session_id,
            ip,
            mac: (0..6)
                .map(|i| &mac[i * 2..i * 2 + 2])
                .collect::<Vec<_>>()
                .join(":"),
        });
    }
    Ok(devices)
}

fn logout_with(
    client: &impl Transport,
    username: &str,
    devices: &[AccountDevice],
    device: &AccountDevice,
    current: &impl Fn() -> bool,
) -> Result<LogoutResult, String> {
    if !devices.contains(device) {
        return Err(strings::DEVICE_STALE.into());
    }
    let mac = canonical_mac(&device.mac).ok_or(strings::DEVICE_STALE)?;
    // Verify the portal identity again, using only the already established cookie
    // session. An expired session must never silently renew and replay a write.
    let identity = get_current(client, current, Host::Lgn, false, "/")?;
    if !identity.success() || parse_identity(&identity.text()).as_deref() != Some(username) {
        return Err(strings::DEVICE_STALE.into());
    }
    if !current() {
        return Err(strings::NETWORK_CHANGED.into());
    }
    let path = format!(
        "/Self/dashboard/tooffline?{}",
        query(&[
            ("sessionid", &device.session_id),
            ("ip", &device.ip),
            ("mac", &mac),
        ])
    );
    // Once dispatched, transport/HTTP failures cannot prove whether the server
    // performed the operation. Refresh the list, without replaying the request.
    let response = client.get(Host::SelfService, false, &path);
    if !current() {
        return Err(strings::NETWORK_CHANGED.into());
    }
    let Ok(response) = response else {
        return Ok(LogoutResult::Indeterminate);
    };
    if (300..400).contains(&response.status) {
        return Err(strings::ACCOUNT_EXPIRED.into());
    }
    if !response.success() {
        return Ok(LogoutResult::Indeterminate);
    }
    Ok(parse_logout(&response.text()))
}

fn parse_logout(text: &str) -> LogoutResult {
    let Ok(data) = serde_json::from_str::<Value>(text) else {
        return LogoutResult::Indeterminate;
    };
    let success = match &data["success"] {
        Value::Bool(value) => Some(*value),
        Value::Number(value) => value.as_f64().map(|value| value != 0.0),
        Value::String(value) => match value.trim().to_ascii_lowercase().as_str() {
            "true" | "1" => Some(true),
            "false" | "0" => Some(false),
            _ => None,
        },
        _ => None,
    };
    match success {
        Some(true) => LogoutResult::Success,
        Some(false) => LogoutResult::Failure,
        None => LogoutResult::Indeterminate,
    }
}

fn get_current(
    client: &impl Transport,
    current: &impl Fn() -> bool,
    host: Host,
    api: bool,
    path: &str,
) -> Result<Response, String> {
    if !current() {
        return Err(strings::NETWORK_CHANGED.into());
    }
    let response = client
        .get(host, api, path)
        .map_err(|_| strings::ACCOUNT_FAILED)?;
    if !current() {
        return Err(strings::NETWORK_CHANGED.into());
    }
    if !response.success() && !(300..400).contains(&response.status) {
        return Err(strings::ACCOUNT_FAILED.into());
    }
    Ok(response)
}

fn load_overview(
    client: &impl Transport,
    network: &Network,
    username: &str,
    current: &impl Fn() -> bool,
) -> Result<AccountOverview, String> {
    let response = get_current(
        client,
        current,
        Host::Lgn,
        true,
        &sso_path(username, &network.ipv4),
    )?;
    if !response.success() {
        return Err(strings::SSO_FAILED.into());
    }
    let sso = extract_object(&response.text(), None).ok_or(strings::SSO_FAILED)?;
    if sso["result"] != 1 && sso["result"] != "1" {
        return Err(strings::SSO_FAILED.into());
    }
    let auth = sso["self_auth_url"].as_str().ok_or(strings::SSO_FAILED)?;
    let auth_path = trusted_path(auth, "/Self/").ok_or(strings::SSO_URL_INVALID)?;
    follow(client, current, auth_path)?;
    let dashboard = follow(client, current, "/Self/dashboard".into())?;
    parse_overview(&dashboard, username).map_err(str::to_owned)
}

fn follow(
    client: &impl Transport,
    current: &impl Fn() -> bool,
    mut path: String,
) -> Result<String, String> {
    for _ in 0..6 {
        let response = get_current(client, current, Host::SelfService, false, &path)?;
        if (300..400).contains(&response.status) {
            path = trusted_path(&response.location, &path).ok_or(strings::SSO_URL_INVALID)?;
        } else if response.success() {
            if path
                .split('?')
                .next()
                .unwrap_or_default()
                .to_ascii_lowercase()
                .starts_with("/self/login")
            {
                return Err(strings::ACCOUNT_EXPIRED.into());
            }
            return Ok(response.text());
        } else {
            return Err(strings::ACCOUNT_FAILED.into());
        }
    }
    Err(strings::SSO_FAILED.into())
}

fn parse_identity(page: &str) -> Option<String> {
    let logout = page.contains("Dr.COMWebLoginID_1.htm") || {
        let title = regex::Regex::new(r"(?i)<title\b[^>]*>\s*注销页\s*</title\s*>").unwrap();
        title.is_match(page)
    };
    if !logout {
        return None;
    }
    let username = page_variable(page, "uid");
    if username.is_empty() || username.len() > 128 || username.chars().any(char::is_control) {
        None
    } else {
        Some(username)
    }
}

fn parse_overview(page: &str, expected: &str) -> Result<AccountOverview, &'static str> {
    let data = extract_object(page, Some("})(")).ok_or(strings::ACCOUNT_FAILED)?;
    if let Some(name) = data.get("userName") {
        let name = name.as_str().ok_or(strings::ACCOUNT_FAILED)?.trim();
        if !name.is_empty() && account_key(name) != account_key(expected) {
            return Err(strings::ACCOUNT_MISMATCH);
        }
    }
    // Reject unrelated JSON rather than rendering an empty successful overview.
    if !["internetDownFlow", "leftFlow", "leftMoney"]
        .iter()
        .any(|key| data.get(*key).is_some())
    {
        return Err(strings::ACCOUNT_FAILED);
    }
    Ok(AccountOverview {
        used_megabytes: number(&data["internetDownFlow"], false),
        remaining_megabytes: number(&data["leftFlow"], false),
        money_yuan: number(&data["leftMoney"], true),
    })
}

fn number(value: &Value, allow_negative: bool) -> Option<f64> {
    let number = value
        .as_f64()
        .or_else(|| value.as_str()?.trim().parse().ok())?;
    (number.is_finite() && (allow_negative || number >= 0.0)).then_some(number)
}
fn account_key(account: &str) -> &str {
    account.split('@').next().unwrap_or_default().trim()
}
fn xor(value: &str) -> String {
    value
        .encode_utf16()
        .map(|ch| format!("{:02x}", ch ^ 0x16))
        .collect()
}
fn sso_path(username: &str, ip: &str) -> String {
    format!(
        "/eportal/portal/self?{}",
        query(&[
            ("callback", &xor("dr1004")),
            ("self_type", &xor("1")),
            ("user_account", &xor(account_key(username))),
            ("user_password", ""),
            ("wlan_user_mac", &xor("000000000000")),
            ("wlan_user_ip", &xor(ip)),
            ("jsVersion", "2238243824"),
            ("program_index", "79225954737327212323222f212e2723"),
            ("page_index", "755e577b7c4e27212323222f212e2320"),
            ("encrypt", "1"),
            ("v", "4082"),
            ("lang", "zh"),
        ])
    )
}

/// Follow only HTTPS redirects within the self-service application. Tokens are
/// never sent to a different host/port, a downgraded scheme or an arbitrary URL.
fn trusted_path(location: &str, current: &str) -> Option<String> {
    if location.is_empty()
        || location
            .chars()
            .any(|ch| ch.is_control() || ch == '\\' || ch.is_whitespace())
        || location.contains('#')
    {
        return None;
    }
    let path = if let Some((scheme, rest)) = location.split_once("://") {
        if !scheme.eq_ignore_ascii_case("https") {
            return None;
        }
        let (authority, tail) = rest.split_once('/')?;
        if !authority.eq_ignore_ascii_case("jfself.bjut.edu.cn")
            && !authority.eq_ignore_ascii_case("jfself.bjut.edu.cn:443")
        {
            return None;
        }
        format!("/{tail}")
    } else if location.starts_with('/') {
        if location.starts_with("//") {
            return None;
        }
        location.into()
    } else if location.starts_with('?') {
        format!("{}{location}", current.split('?').next()?)
    } else {
        if location.contains(':') {
            return None;
        }
        format!(
            "{}/{location}",
            current.split('?').next()?.rsplit_once('/')?.0
        )
    };
    let raw_path = path.split('?').next()?;
    if !raw_path.starts_with("/Self/")
        || raw_path.contains('%')
        || raw_path.split('/').any(|part| part == ".." || part == ".")
    {
        return None;
    }
    Some(path)
}

fn extract_object(text: &str, marker: Option<&str>) -> Option<Value> {
    let search = marker.map(|marker| text.find(marker)).unwrap_or(Some(0))?;
    let start = text[search..].find('{')? + search;
    // Stream exactly one value so trailing scripts and braces are not consumed.
    serde_json::Deserializer::from_str(&text[start..])
        .into_iter::<Value>()
        .next()?
        .ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        cell::{Cell, RefCell},
        collections::VecDeque,
    };

    struct Fake(
        RefCell<VecDeque<Response>>,
        RefCell<Vec<(Host, bool, String)>>,
    );
    impl Transport for Fake {
        fn get(&self, host: Host, api: bool, path: &str) -> Result<Response, String> {
            self.1.borrow_mut().push((host, api, path.into()));
            self.0.borrow_mut().pop_front().ok_or("no response".into())
        }
    }
    fn response(status: u32, body: &str, location: &str) -> Response {
        Response {
            status,
            body: body.as_bytes().to_vec(),
            location: location.into(),
        }
    }
    fn identity() -> Response {
        response(
            200,
            "<title>注销页</title><script>uid='student';</script>",
            "",
        )
    }
    fn sso() -> Response {
        response(
            200,
            r#"dr1004({"result":1,"self_auth_url":"https://jfself.bjut.edu.cn/Self/auth?token=test"});"#,
            "",
        )
    }
    fn dashboard() -> Response {
        response(
            200,
            r#"var other={}; (function(u){})({"userName":"student","internetDownFlow":"1024.5","leftFlow":2048,"leftMoney":-2.5}); trailing={};"#,
            "",
        )
    }
    fn fake(responses: Vec<Response>) -> Fake {
        Fake(RefCell::new(responses.into()), RefCell::new(Vec::new()))
    }

    fn device() -> AccountDevice {
        parse_devices(r#"[{"sessionId":"s&1","ip":"10.1.2.3","mac":"aabbccddeeff"}]"#)
            .unwrap()
            .remove(0)
    }

    #[test]
    fn device_list_validates_every_row_and_keeps_empty_distinct_from_unavailable() {
        assert_eq!(device().mac, "AA:BB:CC:DD:EE:FF");
        assert_eq!(parse_devices("[]"), Ok(vec![]));
        for text in [
            "{}",
            "null",
            "<html>login</html>",
            r#"[{"sessionId":"s","ip":"","mac":"aabbccddeeff"}]"#,
            r#"[{"sessionId":"s","ip":"10.1.2.3","mac":"invalid"}]"#,
            r#"[{"sessionId":"s","ip":"10.1.2.3","mac":"aabbccddeeff"},{}]"#,
            r#"[{"sessionId":"s","ip":"1","mac":"aabbccddeeff"},{"sessionId":"s","ip":"2","mac":"aabbccddeeff"}]"#,
        ] {
            assert!(parse_devices(text).is_err(), "{text}");
        }
        for (body, available) in [("[]", true), ("malformed", false)] {
            let client = fake(vec![
                identity(),
                sso(),
                response(200, "ready", ""),
                dashboard(),
                response(200, body, ""),
            ]);
            let info = load_with(&client, &Network::default(), &|| true);
            assert!(info.overview.is_some());
            assert_eq!(info.username.as_deref(), Some("student"));
            assert_eq!(info.devices.is_some(), available);
            assert_eq!(info.devices_error.is_some(), !available);
            assert_eq!(
                client.1.borrow().last().unwrap().2,
                "/Self/dashboard/getOnlineList"
            );
        }
    }

    #[test]
    fn logout_uses_existing_session_encodes_values_and_never_follows_or_replays_writes() {
        let device = device();
        for (reply, expected) in [
            (
                response(200, r#"{"success":true}"#, ""),
                Ok(LogoutResult::Success),
            ),
            (
                response(200, r#"{"success":false}"#, ""),
                Ok(LogoutResult::Failure),
            ),
            (
                response(200, "unknown", ""),
                Ok(LogoutResult::Indeterminate),
            ),
            (response(503, "", ""), Ok(LogoutResult::Indeterminate)),
            (
                response(302, "", "/Self/login"),
                Err(strings::ACCOUNT_EXPIRED.into()),
            ),
        ] {
            let client = fake(vec![identity(), reply]);
            assert_eq!(
                logout_with(
                    &client,
                    "student",
                    std::slice::from_ref(&device),
                    &device,
                    &|| true
                ),
                expected
            );
            let requests = client.1.borrow();
            assert_eq!(requests.len(), 2);
            assert_eq!(requests[1].0, Host::SelfService);
            assert_eq!(
                requests[1].2,
                "/Self/dashboard/tooffline?sessionid=s%261&ip=10.1.2.3&mac=AABBCCDDEEFF"
            );
        }
        for (text, expected) in [
            (r#"{"success":"1"}"#, LogoutResult::Success),
            (r#"{"success":0}"#, LogoutResult::Failure),
            (r#"{"result":1}"#, LogoutResult::Indeterminate),
        ] {
            assert_eq!(parse_logout(text), expected);
        }
    }

    #[test]
    fn logout_rejects_stale_devices_identity_and_network_before_submission() {
        let device = device();
        let client = fake(vec![]);
        assert!(logout_with(&client, "student", &[], &device, &|| true).is_err());
        assert!(client.1.borrow().is_empty());
        let client = fake(vec![identity()]);
        assert!(
            logout_with(
                &client,
                "another",
                std::slice::from_ref(&device),
                &device,
                &|| true
            )
            .is_err()
        );
        assert_eq!(client.1.borrow().len(), 1);
        let client = fake(vec![identity()]);
        let checks = Cell::new(0);
        assert!(
            logout_with(
                &client,
                "student",
                std::slice::from_ref(&device),
                &device,
                &|| {
                    checks.set(checks.get() + 1);
                    checks.get() < 3
                }
            )
            .is_err()
        );
        assert_eq!(client.1.borrow().len(), 1);
        let client = fake(vec![identity()]);
        assert_eq!(
            logout_with(
                &client,
                "student",
                std::slice::from_ref(&device),
                &device,
                &|| true
            ),
            Ok(LogoutResult::Indeterminate)
        );
        assert_eq!(client.1.borrow().len(), 2);
    }

    #[test]
    fn follows_sso_redirects_and_loads_verified_account() {
        let client = fake(vec![
            identity(),
            sso(),
            response(302, "", "/Self/"),
            response(200, "ready", ""),
            dashboard(),
        ]);
        let info = load_with(
            &client,
            &Network {
                ipv4: "10.1.2.3".into(),
                ..Default::default()
            },
            &|| true,
        );
        assert_eq!(info.username.as_deref(), Some("student"));
        assert_eq!(
            info.overview.unwrap(),
            AccountOverview {
                used_megabytes: Some(1024.5),
                remaining_megabytes: Some(2048.0),
                money_yuan: Some(-2.5)
            }
        );
        let requests = client.1.borrow();
        assert_eq!(requests[1].0, Host::Lgn);
        assert!(requests[1].1);
        assert!(requests[1].2.contains("user_account=65626372737862"));
    }
    #[test]
    fn overview_failure_preserves_verified_identity() {
        let client = fake(vec![identity(), response(503, "unavailable", "")]);
        let info = load_with(&client, &Network::default(), &|| true);
        assert_eq!(info.username.as_deref(), Some("student"));
        assert!(info.overview.is_none());
        assert!(info.error.is_some());
    }
    #[test]
    fn unverified_identity_never_opens_sso() {
        let client = fake(vec![response(
            200,
            "<title>上网登录页</title>uid='student';",
            "",
        )]);
        assert!(
            load_with(&client, &Network::default(), &|| true)
                .username
                .is_none()
        );
        assert_eq!(client.1.borrow().len(), 1);
    }
    #[test]
    fn rejects_cross_account_dashboard_and_formats_missing_or_invalid_values() {
        assert_eq!(
            parse_overview(r#"})({"userName":"other","leftMoney":20});"#, "student"),
            Err(strings::ACCOUNT_MISMATCH)
        );
        let overview = parse_overview(
            r#"})({"leftMoney":"NaN","leftFlow":null,"internetDownFlow":-1});"#,
            "student",
        )
        .unwrap();
        assert_eq!(
            overview,
            AccountOverview {
                used_megabytes: None,
                remaining_megabytes: None,
                money_yuan: None
            }
        );
        assert_eq!(
            parse_overview("missing marker {}", "student"),
            Err(strings::ACCOUNT_FAILED)
        );
        assert_eq!(
            extract_object(r#"cb({"a":"}\\\"","b":{"c":1}}); more={}"#, None).unwrap()["b"]["c"],
            1
        );
    }
    #[test]
    fn redirects_cannot_leak_sso_tokens_to_untrusted_targets() {
        for target in [
            "http://jfself.bjut.edu.cn/Self/",
            "https://jfself.bjut.edu.cn.evil/Self/",
            "https://jfself.bjut.edu.cn@evil/Self/",
            "https://jfself.bjut.edu.cn:444/Self/",
            "//evil/Self/",
            "/Self/../outside",
            "/Self/%2e%2e/outside",
            "https://evil/Self/",
            "/other",
            "/Self/\\evil",
        ] {
            assert!(trusted_path(target, "/Self/auth").is_none(), "{target}");
        }
        assert_eq!(
            trusted_path("dashboard", "/Self/auth").as_deref(),
            Some("/Self/dashboard")
        );
        let client = fake(vec![
            identity(),
            sso(),
            response(302, "", "https://evil/Self/?token=test"),
        ]);
        assert!(
            load_with(&client, &Network::default(), &|| true)
                .overview
                .is_none()
        );
        assert_eq!(client.1.borrow().len(), 3);
    }
    #[test]
    fn network_change_discards_identity_and_stops_further_requests() {
        let client = fake(vec![identity(), sso(), dashboard()]);
        let calls = Cell::new(0);
        let info = load_with(&client, &Network::default(), &|| {
            calls.set(calls.get() + 1);
            calls.get() < 3
        });
        assert_eq!(info, AccountInfo::default());
        assert_eq!(client.1.borrow().len(), 1);
    }
    #[test]
    fn expired_sessions_and_redirect_loops_are_bounded() {
        let client = fake(vec![response(200, "login", "")]);
        assert!(follow(&client, &|| true, "/Self/login".into()).is_err());
        let client = fake((0..6).map(|_| response(302, "", "/Self/loop")).collect());
        assert!(follow(&client, &|| true, "/Self/loop".into()).is_err());
        assert_eq!(client.1.borrow().len(), 6);
    }

    #[test]
    fn gbk_identity_is_decoded_and_redirect_responses_are_not_identity_proof() {
        let mut page = identity();
        page.body = encoding_rs::GBK
            .encode("<title>注销页</title>uid='student';")
            .0
            .into_owned();
        let client = fake(vec![page, response(500, "", "")]);
        assert_eq!(
            load_with(&client, &Network::default(), &|| true)
                .username
                .as_deref(),
            Some("student")
        );
        let mut page = identity();
        page.status = 302;
        let client = fake(vec![page]);
        assert!(
            load_with(&client, &Network::default(), &|| true)
                .username
                .is_none()
        );
        assert_eq!(client.1.borrow().len(), 1);
    }
}
