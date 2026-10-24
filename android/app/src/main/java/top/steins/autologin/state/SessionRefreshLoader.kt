package top.steins.autologin.state

import top.steins.autologin.domain.model.AccountOverviewResult
import top.steins.autologin.domain.model.CurrentNetworkInfo
import top.steins.autologin.domain.model.WifiLoginMethod
import top.steins.autologin.domain.port.AccountSessionGateway
import top.steins.autologin.domain.port.LoginStatusReader
import top.steins.autologin.domain.port.NetworkMonitor
import top.steins.autologin.domain.port.WifiConfigStore

/** 加载网络、认证身份和账号概览，不持有主页状态、任务或会话锁。 */
internal class SessionRefreshLoader(
    private val network: NetworkMonitor,
    private val authentication: LoginStatusReader,
    private val wifiConfig: WifiConfigStore,
    private val selfService: AccountSessionGateway,
    private val preferredMethod: (String) -> WifiLoginMethod?
) {
    /** 调用方持有会话执行权；进度通知同步交给协调器进行代次校验。 */
    suspend fun load(
        canReadWifiName: Boolean,
        clearSession: Boolean,
        knownLoggedIn: Boolean,
        onProgress: (SessionRefreshProgress) -> Unit
    ): SessionRefreshResult {
        if (clearSession) selfService.clearSession()
        val info = network.fetchCurrentNetworkInfo(canReadWifiName)
        onProgress(SessionRefreshProgress.NetworkRead(info))
        if (info.isWifi) wifiConfig.addAutoDetectedTargetWifi(info.wifiName)
        if (!info.isWifi || info.wifiName !in wifiConfig.targetSsids.value) {
            onProgress(SessionRefreshProgress.OutsideTargetNetwork(info))
            selfService.clearSession()
            return SessionRefreshResult.OutsideTargetNetwork(info)
        }

        onProgress(SessionRefreshProgress.CheckingAuthentication)
        val status = authentication.fetchLoginStatus(
            knownLoggedIn = knownLoggedIn,
            preferredMethod = preferredMethod(info.wifiName)
        )
        if (status.error.isBlank() && status.verifiedMethod != null) {
            val current = network.fetchCurrentNetworkInfo(canReadWifiName)
            // 网络切换后返回的旧结果不能覆盖该 Wi-Fi 的验证方式。
            if (current.isWifi && current.wifiName == info.wifiName && current.ipAddress == info.ipAddress) {
                wifiConfig.saveWifiLoginMethod(info.wifiName, status.verifiedMethod)
            }
        }
        if (!status.isLoggedIn) {
            if (status.error.isNotBlank()) {
                return SessionRefreshResult.AuthenticationUnavailable(info, status.error)
            }
            onProgress(SessionRefreshProgress.IdentityCleared)
            selfService.clearSession()
            return SessionRefreshResult.Offline(info)
        }

        // 身份在加载概览前交给协调器，概览失败不能抹掉门户确认的身份。
        onProgress(SessionRefreshProgress.Authenticated(info, status.uid))
        if (status.uid.isBlank()) {
            selfService.clearSession()
            return SessionRefreshResult.IdentityUnavailable(info)
        }
        return SessionRefreshResult.AccountLoaded(selfService.loadAccountOverview(status.uid))
    }
}

internal sealed interface SessionRefreshProgress {
    data class NetworkRead(val info: CurrentNetworkInfo) : SessionRefreshProgress
    data class OutsideTargetNetwork(val info: CurrentNetworkInfo) : SessionRefreshProgress
    data object CheckingAuthentication : SessionRefreshProgress
    data object IdentityCleared : SessionRefreshProgress
    data class Authenticated(val info: CurrentNetworkInfo, val username: String) : SessionRefreshProgress
}

internal sealed interface SessionRefreshResult {
    data class OutsideTargetNetwork(val info: CurrentNetworkInfo) : SessionRefreshResult
    data class Offline(val info: CurrentNetworkInfo) : SessionRefreshResult
    data class IdentityUnavailable(val info: CurrentNetworkInfo) : SessionRefreshResult
    data class AuthenticationUnavailable(val info: CurrentNetworkInfo, val error: String) : SessionRefreshResult
    data class AccountLoaded(val result: AccountOverviewResult) : SessionRefreshResult
}
