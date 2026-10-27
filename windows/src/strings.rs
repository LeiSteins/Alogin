pub const APP: &str = "Alogin";
pub const ICON_FAILED: &str = "无法加载应用图标资源。";
pub const SHOW: &str = "打开 Alogin";
pub const LOGIN: &str = "登录";
pub const REFRESH: &str = "刷新状态";
pub const EXIT: &str = "退出";
pub const TRAY_WIFI: &str = "当前 Wi-Fi";
pub const TRAY_NETWORK: &str = "当前网络";
pub const TRAY_LOGGED_IN: &str = "已登录";
pub const TRAY_STARTUP_UNKNOWN: &str = "开机自启（状态不可用）";
pub const HOME_PAGE: &str = "主页";
pub const ACCOUNTS_PAGE: &str = "账号管理";
pub const NETWORK_PAGE: &str = "目标网络";
pub const MAINTENANCE_PAGE: &str = "登录维持";
pub const MAINTENANCE_SUMMARY: &str =
    "在目标 Wi-Fi 上持续检测校园网认证状态，确认掉线后自动尝试登录。关闭主窗口后仍在托盘运行。";
pub const MAINTENANCE_STATE: &str = "运行状态";
pub const CHECK_INTERVAL: &str = "登录状态检测间隔";
pub const RETRY_INITIAL: &str = "首次失败重试间隔";
pub const RETRY_MAX: &str = "失败重试间隔上限";
pub const RETRY_SUMMARY: &str =
    "登录失败后逐次延长重试间隔；状态未知时仅继续检测，账号或密码错误时暂停自动登录。";
pub const PAUSE_AFTER_LOGOUT: &str = "主动下线本机后暂停自动重登";
pub const RESUME_MAINTENANCE: &str = "恢复检测与重登";
pub const MAINTENANCE_RESUMED: &str = "已恢复登录维持，将重新检查认证状态。";
pub const MAINTENANCE_OFF: &str = "自动登录未开启";
pub const MAINTENANCE_WAIT_NETWORK: &str = "等待连接已保存的目标 Wi-Fi";
pub const MAINTENANCE_PASSWORD: &str = "自动重登已暂停：请检查账号或密码";
pub const MAINTENANCE_LOGOUT: &str = "自动重登已暂停：本机已主动下线";
pub const MAINTENANCE_ONLINE: &str = "持续检测已开启";
pub const MAINTENANCE_WAITING: &str = "等待检测或重试";
pub const MAINTENANCE_CHECK_ONLINE: &str = "最近检测：已认证，将按设置间隔继续检测。";
pub const MAINTENANCE_CHECK_OFFLINE: &str = "最近检测：尚未认证，满足重试条件时自动登录。";
pub const MAINTENANCE_CHECK_UNKNOWN: &str = "最近检测：暂时无法确认状态，将退避后继续检测。";
pub const INVALID_MAINTENANCE: &str = "登录维持设置无效，请检查检测及重试间隔。";
pub const SECONDS_15: &str = "15 秒";
pub const SECONDS_30: &str = "30 秒";
pub const SECONDS_60: &str = "60 秒";
pub const SECONDS_120: &str = "120 秒";
pub const MINUTES_2: &str = "2 分钟";
pub const MINUTES_5: &str = "5 分钟";
pub const MINUTES_10: &str = "10 分钟";
pub const SETTINGS_PAGE: &str = "设置";
pub const STARTUP: &str = "开机自启";
pub const STARTUP_SUMMARY: &str =
    "登录 Windows 后自动在托盘运行；需要自动认证时，请同时开启自动登录与掉线重登。";
pub const STARTUP_READ_FAILED: &str = "无法读取开机自启状态，请重新设置或重启应用后重试。";
pub const STARTUP_WRITE_FAILED: &str = "无法修改开机自启设置，请检查当前用户的启动项权限后重试。";
pub const STARTUP_PATH_FAILED: &str =
    "当前程序路径无法用于开机自启，请将程序放到较短的本地绝对路径后重试。";
pub const ABOUT_PAGE: &str = "关于";
pub const APPEARANCE: &str = "外观设置";
pub const APPEARANCE_SYSTEM: &str = "跟随系统";
pub const APPEARANCE_LIGHT: &str = "浅色";
pub const APPEARANCE_DARK: &str = "深色";
pub const ABOUT_DESCRIPTION: &str =
    "一款 Windows 网络认证助手，用于在已配置的目标网络上完成认证，并查看网络与账号状态。";
pub const VERSION_LABEL: &str = "版本";
pub const IDENTIFIER_LABEL: &str = "应用标识";
pub const IDENTIFIER: &str = "top.steins.autologin.windows";
pub const LICENSE_LABEL: &str = "开源许可";
pub const HOMEPAGE: &str = "项目主页";
pub const HOMEPAGE_SUMMARY: &str = "访问项目官方网站";
pub const SOURCE: &str = "项目源码";
pub const SOURCE_SUMMARY: &str = "在 GitHub 上查看源代码";
pub const LICENSE_DETAILS: &str = "许可说明";
pub const LICENSE_SUMMARY: &str = "查看完整许可条款";
pub const CHECK_UPDATES: &str = "检查更新";
pub const UPDATES_UNAVAILABLE: &str = "暂未接入更新服务";
pub const OPEN_LINK_FAILED: &str = "无法打开链接，请检查默认浏览器设置。";
pub const ABOUT_LINKS: [&str; 3] = [
    "https://steins.top/alogin/",
    "https://github.com/LeiSteins/Alogin",
    "https://github.com/LeiSteins/Alogin/blob/main/LICENSE",
];
pub const TOGGLE_NAVIGATION: &str = "展开或收起导航";
pub const ACCOUNTS: &str = "已保存账号";
pub const NO_SAVED_ACCOUNTS: &str = "暂无已保存账号";
pub const USERNAME: &str = "账号";
pub const PASSWORD: &str = "密码（留空保留已保存密码）";
pub const SAVE: &str = "保存并使用";
pub const ADD_ACCOUNT: &str = "添加账号";
pub const EDIT_ACCOUNT: &str = "修改账号";
pub const CANCEL: &str = "取消";
pub const DELETE: &str = "删除账号";
pub const TARGETS: &str = "目标 Wi-Fi";
pub const WIFI_NAME: &str = "Wi-Fi 完整名称";
pub const ADD_WIFI: &str = "添加自定义WiFi";
pub const ADD: &str = "添加";
pub const DELETE_WIFI: &str = "删除 Wi-Fi";
pub const NO_TARGETS: &str = "暂无目标 Wi-Fi";
pub const INVALID_WIFI: &str = "请输入一个完整的 Wi-Fi 名称。";
pub const ADD_CURRENT_WIFI: &str = "添加当前连接的 Wi-Fi";
pub const AUTO: &str = "自动登录与掉线重登";
pub const LOADING: &str = "正在读取本地配置…";
pub const BUSY: &str = "正在处理…";
pub const READY: &str = "就绪，请添加账号或刷新网络状态。";
pub const SAVED: &str = "已保存。";
pub const DELETED: &str = "账号已删除。";
pub const SELECTED: &str = "已切换当前账号。";
pub const AUTHENTICATED: &str = "认证成功";
pub const ALREADY_ONLINE: &str = "当前 IP 已在线";
pub const LOGGED_IN: &str = "已认证";
pub const LOGGED_OUT: &str = "尚未认证";
pub const UNKNOWN: &str = "认证状态暂时无法确认";
pub const NO_ACCOUNT: &str = "请先保存并选择账号。";
pub const INVALID_ACCOUNT: &str = "请输入账号和密码。";
pub const NO_NETWORK: &str = "未找到可用的校园网 IPv4 路由，请检查网络连接。";
pub const PORTAL_IP_UNAVAILABLE: &str = "门户未返回有效的校园网 IP 地址，请刷新后重试。";
pub const NETWORK_CHANGED: &str = "网络已变化，已取消本次登录，请重新尝试。";
pub const UNKNOWN_RESPONSE: &str = "无法识别门户响应，请刷新状态后再试。";
pub const INVALID_PASSWORD: &str = "账号或密码错误。";
pub const LOGIN_FAILED: &str = "认证失败，请检查账号或网络。";
pub const BAD_FILE: &str = "本地配置损坏或版本不兼容，已保留原文件，请备份后处理。";
pub const STORAGE_FAILED: &str = "无法保存配置，原配置已保留。";
pub const DECRYPT_FAILED: &str = "无法解密凭据，请使用保存凭据时的 Windows 用户。";
pub const ENCRYPT_FAILED: &str = "Windows 凭据加密失败，未保存账号。";
pub const RESPONSE_TOO_LARGE: &str = "门户响应超过大小限制。";
pub const SINGLE_INSTANCE: &str = "Alogin 已在运行，请从系统托盘打开。";
pub const MUTEX_FAILED: &str = "无法创建应用实例锁。";
pub const REGISTER_WINDOW_FAILED: &str = "无法注册窗口。";
pub const HOST_FAILED: &str = "无法创建后台窗口。";
pub const MESSAGE_LOOP_FAILED: &str = "Windows 消息循环失败。";
pub const TRAY_FAILED: &str = "无法创建系统托盘图标。";
pub const WINDOW_FAILED: &str = "无法创建主窗口。";
pub const THREAD_FAILED: &str = "无法启动后台工作线程。";
pub const THREAD_STOPPED: &str = "后台线程已停止，请重新启动应用。";
pub const SSID_TOO_LONG: &str = "Wi-Fi 名称不能超过 32 字节。";
pub const DISCONNECTED: &str = "未连接";
pub const ETHERNET: &str = "有线网络";
pub const SSID_UNAVAILABLE: &str = "Wi-Fi 名称不可用";
pub const APP_DATA_FAILED: &str = "无法找到 Windows 本地应用数据目录。";
pub const READ_FAILED: &str = "无法读取本地配置，请检查文件权限。";
pub const SUBSCRIBE_FAILED: &str = "无法订阅 Windows 网络变化通知。";
pub const REQUEST_TIMEOUT: &str = "门户请求超时，请稍后重试。";
pub const DEVICES_TITLE: &str = "在线设备";
pub const NO_DEVICES: &str = "当前没有在线设备";
pub const CURRENT_DEVICE: &str = "本机";
pub const LOGOUT_DEVICE: &str = "下线设备";
pub const DEVICES_FAILED: &str = "在线设备列表暂时无法读取，请刷新后重试。";
pub const DEVICE_STALE: &str = "设备或会话已变化，请刷新账号信息后重试。";
pub const DEVICE_LOGOUT_FAILED: &str = "校园网系统未能下线该设备，请刷新后重试。";
pub const DEVICE_LOGOUT_SUCCESS: &str = "设备已下线。";
pub const DEVICE_LOGOUT_UNKNOWN: &str = "无法确认下线结果，已刷新状态，请核对在线设备列表。";
pub const ACCOUNT_TITLE: &str = "已登录";
pub const ACCOUNT_USERNAME: &str = "用户名";
pub const REMAINING_MONEY: &str = "剩余金额";
pub const RETRY_FETCH: &str = "重试获取";
pub const NETWORK_TITLE: &str = "网络状态";
pub const NETWORK_NAME: &str = "网络";
pub const NETWORK_IPV4: &str = "IPv4";
pub const TRAFFIC_PROGRESS: &str = "流量使用情况";
pub const ACCOUNT_FAILED: &str = "账号概览暂时无法读取，请稍后刷新。";
pub const IDENTITY_FAILED: &str = "已认证，暂时无法确认在线账号，请稍后刷新。";
pub const SSO_FAILED: &str = "自助服务会话建立失败，请稍后刷新。";
pub const SSO_URL_INVALID: &str = "自助服务返回了不受信任的跳转地址。";
pub const ACCOUNT_MISMATCH: &str = "自助服务返回的账号与当前在线账号不一致，请刷新。";
pub const ACCOUNT_EXPIRED: &str = "自助服务会话已过期，请刷新。";

#[derive(Default)]
pub struct AccountCardText {
    pub username: String,
    pub balance: String,
    pub traffic_amount: String,
    pub used_percent: String,
    pub progress: Option<f32>,
    pub has_overview: bool,
    pub error: String,
}

pub fn account_card(
    info: &crate::domain::AccountInfo,
    online: crate::domain::Online,
) -> AccountCardText {
    if online != crate::domain::Online::Yes {
        return AccountCardText::default();
    }
    let Some(username) = &info.username else {
        return AccountCardText {
            error: info.error.as_deref().unwrap_or(IDENTITY_FAILED).into(),
            ..AccountCardText::default()
        };
    };
    let Some(overview) = &info.overview else {
        return AccountCardText {
            username: username.clone(),
            error: info.error.as_deref().unwrap_or(ACCOUNT_FAILED).into(),
            ..AccountCardText::default()
        };
    };
    let progress = overview.traffic_usage_fraction();
    let valid_traffic = |value: &f64| value.is_finite() && *value >= 0.0;
    let used = overview.used_megabytes.filter(valid_traffic);
    let total = used
        .zip(overview.remaining_megabytes.filter(valid_traffic))
        .map(|(used, remaining)| used + remaining)
        .filter(|total| total.is_finite());
    AccountCardText {
        username: username.clone(),
        balance: overview
            .money_yuan
            .filter(|value| value.is_finite())
            .map(|value| format!("{value:.2} 元"))
            .unwrap_or_default(),
        traffic_amount: format!("{} / {}", traffic(used), traffic(total)),
        used_percent: progress
            .map(traffic_used_percent)
            .unwrap_or_else(|| "—".into()),
        progress,
        has_overview: true,
        error: info.error.clone().unwrap_or_default(),
    }
}

fn traffic(value: Option<f64>) -> String {
    match value.filter(|value| value.is_finite() && *value >= 0.0) {
        Some(value) => {
            let (value, unit) = if value >= 1024.0 {
                (value / 1024.0, "G")
            } else {
                (value, "M")
            };
            let number = format!("{value:.1}");
            format!("{}{unit}", number.strip_suffix(".0").unwrap_or(&number))
        }
        None => "—".into(),
    }
}

fn traffic_used_percent(usage_fraction: f32) -> String {
    // Match Android's integer percentage display.
    format!("已用 {}%", (usage_fraction.clamp(0.0, 1.0) * 100.0) as u32)
}

pub fn request_error(code: u32) -> String {
    format!("校园网请求失败（Windows 错误 {code}）。")
}
pub fn device_ip(ip: &str) -> String {
    format!("IP：{ip}")
}
pub fn detection_error(status: u32) -> String {
    format!("门户探测失败（HTTP {status}）。")
}
pub fn login_http_error(status: u32) -> String {
    format!("认证服务返回 HTTP {status}。")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::domain::{AccountInfo, AccountOverview, Online};

    #[test]
    fn overview_formats_traffic_balance_and_unknown_fields() {
        let info = AccountInfo {
            username: Some("student".into()),
            overview: Some(AccountOverview {
                used_megabytes: Some(2048.0),
                remaining_megabytes: None,
                money_yuan: Some(-2.5),
            }),
            error: None,
            ..Default::default()
        };
        let text = account_card(&info, Online::Yes);
        assert_eq!(text.username, "student");
        assert_eq!(text.traffic_amount, "2G / —");
        assert_eq!(text.balance, "-2.50 元");
        assert_eq!(text.used_percent, "—");
        assert!(text.progress.is_none());
        assert!(account_card(&info, Online::No).username.is_empty());
        assert!(account_card(&info, Online::Unknown).username.is_empty());
    }

    #[test]
    fn traffic_amount_uses_used_plus_remaining_and_preserves_unknown_totals() {
        for (used, remaining, expected) in [
            (Some(6144.0), Some(24576.0), "6G / 30G"),
            (Some(1536.0), Some(512.0), "1.5G / 2G"),
            (Some(256.0), Some(512.0), "256M / 768M"),
            (Some(0.0), Some(0.0), "0M / 0M"),
            (None, Some(1024.0), "— / —"),
            (Some(2048.0), Some(-1.0), "2G / —"),
            (Some(f64::NAN), Some(1024.0), "— / —"),
            (Some(2048.0), Some(f64::INFINITY), "2G / —"),
        ] {
            let info = AccountInfo {
                username: Some("student".into()),
                overview: Some(AccountOverview {
                    used_megabytes: used,
                    remaining_megabytes: remaining,
                    money_yuan: None,
                }),
                error: None,
                ..Default::default()
            };
            assert_eq!(account_card(&info, Online::Yes).traffic_amount, expected);
        }
    }

    #[test]
    fn failed_overview_still_displays_confirmed_identity() {
        let info = AccountInfo {
            username: Some("student".into()),
            overview: None,
            error: Some(ACCOUNT_EXPIRED.into()),
            ..Default::default()
        };
        let text = account_card(&info, Online::Yes);
        assert_eq!(text.username, "student");
        assert_eq!(text.error, ACCOUNT_EXPIRED);
        assert!(!text.has_overview);
        assert!(text.traffic_amount.is_empty());
    }
}
