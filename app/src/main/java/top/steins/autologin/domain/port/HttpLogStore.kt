package top.steins.autologin.domain.port

import kotlinx.coroutines.flow.StateFlow
import top.steins.autologin.domain.model.HttpLogEntry

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
