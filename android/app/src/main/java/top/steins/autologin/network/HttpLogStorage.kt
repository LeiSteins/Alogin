package top.steins.autologin.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.steins.autologin.domain.model.HttpLogEntry
import top.steins.autologin.domain.port.HttpLogStore

class HttpLogStorage : HttpLogStore {
    private val _logs = MutableStateFlow<List<HttpLogEntry>>(emptyList())
    override val logs: StateFlow<List<HttpLogEntry>> = _logs.asStateFlow()

    @Volatile
    override var isEnabled: Boolean = false
        private set

    override fun setEnabled(enabled: Boolean) {
        synchronized(this) {
            isEnabled = enabled
            if (!enabled) {
                _logs.value = emptyList()
            }
        }
    }

    override fun add(entry: HttpLogEntry) {
        synchronized(this) {
            if (!isEnabled) return
            // 只保留最近若干条，避免日志无限增长占用内存。
            _logs.value = (_logs.value + entry).takeLast(MAX_LOG_ENTRIES)
        }
    }

    override fun clear() {
        synchronized(this) {
            _logs.value = emptyList()
        }
    }

    private companion object {
        const val MAX_LOG_ENTRIES = 200
    }
}
