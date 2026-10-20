package top.steins.autologin.state

import top.steins.autologin.data.AppearanceMode
import top.steins.autologin.data.SettingsGateway

/** 软件设置与目标 Wi-Fi 配置；跨功能通知由应用容器连接。 */
class SettingsStateHolder(
    private val settings: SettingsGateway,
    private val onAutoLoginDisabled: () -> Unit
) {
    val targetSsids = settings.targetSsids
    val appearanceMode = settings.appearanceMode
    val autoLoginOnStartup = settings.autoLoginOnStartup

    fun addTargetWifi(ssid: String) = settings.addTargetWifi(ssid)

    fun removeTargetWifi(ssid: String) = settings.removeTargetWifi(ssid)

    fun saveAppearanceMode(mode: AppearanceMode) = settings.saveAppearanceMode(mode)

    fun setAutoLoginOnStartup(enabled: Boolean) {
        settings.setAutoLoginOnStartup(enabled)
        if (!enabled) onAutoLoginDisabled()
    }
}
