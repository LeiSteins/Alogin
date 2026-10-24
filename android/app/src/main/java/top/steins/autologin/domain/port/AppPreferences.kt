package top.steins.autologin.domain.port

import kotlinx.coroutines.flow.StateFlow
import top.steins.autologin.domain.model.AppearanceMode

interface AppPreferences {
    val appearanceMode: StateFlow<AppearanceMode>
    val autoLoginOnStartup: StateFlow<Boolean>

    fun saveAppearanceMode(mode: AppearanceMode)
    fun setAutoLoginOnStartup(enabled: Boolean)
}
