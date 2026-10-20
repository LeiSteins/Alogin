package top.steins.autologin.state

import top.steins.autologin.data.HttpLogPreferences
import top.steins.autologin.network.HttpLogStorage

/** 集中管理日志入口解锁与开关，页面不参与认证状态管理。 */
class HttpLogStateHolder(private val preferences: HttpLogPreferences) {
    val httpLogEnabled = preferences.httpLogEnabled
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
        preferences.setHttpLogEnabled(true)
        HttpLogStorage.setEnabled(true)
        versionTapCount = 0
        return true
    }

    fun disableHttpLog() {
        preferences.setHttpLogEnabled(false)
        HttpLogStorage.setEnabled(false)
        versionTapCount = 0
    }

    fun clearHttpLogs() = HttpLogStorage.clear()

    companion object {
        const val HTTP_LOG_UNLOCK_TAP_COUNT = 5
    }
}
