package top.steins.autologin.domain.port

import kotlinx.coroutines.flow.StateFlow

interface HttpLogPreferences {
    val httpLogEnabled: StateFlow<Boolean>

    fun setHttpLogEnabled(enabled: Boolean)
}
