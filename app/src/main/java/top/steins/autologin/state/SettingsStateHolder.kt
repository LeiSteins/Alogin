package top.steins.autologin.state

import top.steins.autologin.data.AppearanceMode
import top.steins.autologin.data.AppPreferences
import top.steins.autologin.data.WifiConfigStore

/** 软件设置与目标 Wi-Fi 配置；跨功能通知由应用容器连接。 */
class SettingsStateHolder(
    private val wifiConfig: WifiConfigStore,
    private val preferences: AppPreferences,
    private val onAutoLoginDisabled: () -> Unit
) {
    val targetSsids = wifiConfig.targetSsids
    val appearanceMode = preferences.appearanceMode
    val autoLoginOnStartup = preferences.autoLoginOnStartup

    fun addTargetWifi(ssid: String) = wifiConfig.addTargetWifi(ssid)

    fun removeTargetWifi(ssid: String) = wifiConfig.removeTargetWifi(ssid)

    fun saveAppearanceMode(mode: AppearanceMode) = preferences.saveAppearanceMode(mode)

    fun setAutoLoginOnStartup(enabled: Boolean) {
        preferences.setAutoLoginOnStartup(enabled)
        if (!enabled) onAutoLoginDisabled()
    }
}
