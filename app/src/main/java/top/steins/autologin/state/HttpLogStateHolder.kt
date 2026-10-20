package top.steins.autologin.state

import top.steins.autologin.data.SettingsGateway
import top.steins.autologin.network.HttpLogStorage

/** 集中管理日志入口解锁与开关，页面不参与认证状态管理。 */
class HttpLogStateHolder(private val settings: SettingsGateway) {
    val httpLogEnabled = settings.httpLogEnabled
    val httpLogs = HttpLogStorage.logs
    private var versionTapCount = 0

    init {
        HttpLogStorage.setEnabled(httpLogEnabled.value)
    }

    /** 返回 true 表示本次点击刚刚解锁了 HTTP 日志。 */
    fun onAboutVersionClicked(): Boolean {
        if (httpLogEnabled.value) return false
        versionTapCount += 1
        if (versionTapCount < HTTP_LOG_UNLOCK_TAP_COUNT) return false
        settings.setHttpLogEnabled(true)
        HttpLogStorage.setEnabled(true)
        versionTapCount = 0
        return true
    }

    fun disableHttpLog() {
        settings.setHttpLogEnabled(false)
        HttpLogStorage.setEnabled(false)
        versionTapCount = 0
    }

    fun clearHttpLogs() = HttpLogStorage.clear()

    companion object {
        const val HTTP_LOG_UNLOCK_TAP_COUNT = 5
    }
}
