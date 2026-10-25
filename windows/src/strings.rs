pub const APP: &str = "Alogin · 校园网认证";
pub const ICON_FAILED: &str = "无法加载应用图标资源。";
pub const SHOW: &str = "打开 Alogin";
pub const LOGIN: &str = "登录";
pub const REFRESH: &str = "刷新状态";
pub const EXIT: &str = "退出";
pub const HOME_PAGE: &str = "主页";
pub const ACCOUNTS_PAGE: &str = "账号管理";
pub const NETWORK_PAGE: &str = "目标网络";
pub const TOGGLE_NAVIGATION: &str = "展开或收起导航";
pub const ACCOUNTS: &str = "已保存账号";
pub const USERNAME: &str = "账号";
pub const PASSWORD: &str = "密码（留空保留已保存密码）";
pub const SAVE: &str = "保存并使用";
pub const DELETE: &str = "删除账号";
pub const TARGETS: &str = "目标 Wi-Fi（每行一个完整名称）";
pub const AUTO: &str = "在启动或网络变化时尝试自动登录";
pub const SAVE_SETTINGS: &str = "保存网络设置";
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
    pub used: String,
    pub remaining: String,
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
    AccountCardText {
        username: username.clone(),
        balance: overview
            .money_yuan
            .filter(|value| value.is_finite())
            .map(|value| format!("{value:.2} 元"))
            .unwrap_or_default(),
        used: format!("已用 {}", traffic(overview.used_megabytes)),
        remaining: format!("剩余 {}", traffic(overview.remaining_megabytes)),
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
        Some(value) if value >= 1024.0 => format!("{:.1} GB", value / 1024.0),
        Some(value) => format!("{value:.1} MB"),
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
        };
        let text = account_card(&info, Online::Yes);
        assert_eq!(text.username, "student");
        assert_eq!(text.used, "已用 2.0 GB");
        assert_eq!(text.remaining, "剩余 —");
        assert_eq!(text.balance, "-2.50 元");
        assert_eq!(text.used_percent, "—");
        assert!(text.progress.is_none());
        assert!(account_card(&info, Online::No).username.is_empty());
        assert!(account_card(&info, Online::Unknown).username.is_empty());
    }

    #[test]
    fn failed_overview_still_displays_confirmed_identity() {
        let info = AccountInfo {
            username: Some("student".into()),
            overview: None,
            error: Some(ACCOUNT_EXPIRED.into()),
        };
        let text = account_card(&info, Online::Yes);
        assert_eq!(text.username, "student");
        assert_eq!(text.error, ACCOUNT_EXPIRED);
        assert!(!text.has_overview);
        assert!(text.used.is_empty());
    }
}
