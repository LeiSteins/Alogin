package top.steins.autologin.domain.port

import top.steins.autologin.domain.model.AccountOverviewResult
import top.steins.autologin.domain.model.DeviceLogoutResult
import top.steins.autologin.domain.model.SeamlessAction
import top.steins.autologin.domain.model.SeamlessActionResult
import top.steins.autologin.domain.model.SeamlessInfo
import top.steins.autologin.domain.model.SeamlessLoadResult

/** 主页使用的账号会话操作，共享同一自助服务会话。 */
interface AccountSessionGateway {
    suspend fun loadAccountOverview(lgnUsername: String): AccountOverviewResult
    suspend fun logoutDevice(
        sessionId: String,
        ipAddress: String,
        macAddress: String
    ): DeviceLogoutResult
    suspend fun clearSession()
}

/** 无感知功能不能清理会话或操作在线设备列表。 */
interface SeamlessGateway {
    suspend fun loadSeamlessInfo(username: String): SeamlessLoadResult
    suspend fun performSeamlessAction(info: SeamlessInfo, action: SeamlessAction): SeamlessActionResult
}
