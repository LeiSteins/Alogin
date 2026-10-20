package top.steins.autologin.data

import kotlinx.coroutines.flow.StateFlow

interface HttpLogPreferences {
    val httpLogEnabled: StateFlow<Boolean>

    fun setHttpLogEnabled(enabled: Boolean)
}
