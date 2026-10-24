package top.steins.autologin.state

import kotlinx.coroutines.flow.StateFlow
import top.steins.autologin.SeamlessUiState
import top.steins.autologin.domain.model.SeamlessAction

/** 无感知页面入口，只提供页面状态、当前 IP 和所需操作。 */
interface SeamlessFeature {
    val state: StateFlow<SeamlessUiState>
    val localIpAddress: StateFlow<String>
    fun refresh()
    fun refreshSession()
    fun perform(action: SeamlessAction)
}
