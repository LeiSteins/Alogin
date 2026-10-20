package top.steins.autologin.data

import kotlinx.coroutines.flow.StateFlow

enum class AppearanceMode {
    SYSTEM,
    LIGHT,
    DARK
}

interface AppPreferences {
    val appearanceMode: StateFlow<AppearanceMode>
    val autoLoginOnStartup: StateFlow<Boolean>

    fun saveAppearanceMode(mode: AppearanceMode)
    fun setAutoLoginOnStartup(enabled: Boolean)
}
