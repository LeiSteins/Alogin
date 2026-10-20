package top.steins.autologin.navigation

/**
 * 应用内页面的唯一入口标识。
 *
 * 路由集中定义，页面不依赖枚举序号推断前进或返回方向。
 */
sealed class AppDestination(val route: String) {
    data object Home : AppDestination("home")
    data object Account : AppDestination("account")
    data object Settings : AppDestination("settings")
    data object SoftwareSettings : AppDestination("software_settings")
    data object About : AppDestination("about")
    data object Log : AppDestination("log")
    data object WifiConfig : AppDestination("wifi_config")
    data object SeamlessLogin : AppDestination("seamless_login")
}
