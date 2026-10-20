package top.steins.autologin.network

import kotlinx.coroutines.flow.StateFlow

/** 网络请求只接收写入能力；开关和日志管理由状态持有者负责。 */
interface HttpLogSink {
    val isEnabled: Boolean
    fun add(entry: HttpLogEntry)
}

interface HttpLogStore : HttpLogSink {
    val logs: StateFlow<List<HttpLogEntry>>
    fun setEnabled(enabled: Boolean)
    fun clear()
}

fun HttpLogSink.logAccountInfoRefresh(reason: String) {
    if (!isEnabled) return
    add(
        HttpLogEntry(
            id = System.nanoTime(),
            method = "REFRESH",
            url = "app://account-info/refresh",
            statusCode = 0,
            timestamp = System.currentTimeMillis(),
            requestBody = "",
            responseBody = "",
            error = null,
            type = HttpLogEntryType.ACCOUNT_INFO_REFRESH,
            eventMessage = reason
        )
    )
}
