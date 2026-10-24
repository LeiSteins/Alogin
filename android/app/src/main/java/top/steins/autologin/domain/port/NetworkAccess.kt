package top.steins.autologin.domain.port

import kotlinx.coroutines.flow.Flow
import top.steins.autologin.domain.model.CampusNetworkChange
import top.steins.autologin.domain.model.CurrentNetworkInfo
import top.steins.autologin.domain.model.LoginResult
import top.steins.autologin.domain.model.LoginStatus
import top.steins.autologin.domain.model.WifiLoginMethod

/** 系统网络观察与当前路由信息，不承担校园网认证。 */
interface NetworkMonitor {
    fun observeCampusNetworkChanges(): Flow<CampusNetworkChange>
    fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo
    suspend fun awaitCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo =
        fetchCurrentNetworkInfo(canReadWifiName)
}

fun interface InternetConnectivity {
    fun hasValidatedInternet(): Boolean
}

/** 无感知操作只需要复核认证状态，不需要发起登录或观察系统网络。 */
interface LoginStatusReader {
    /** knownLoggedIn 仅用于认证接口明确返回当前 IP 已在线后的账号信息刷新。 */
    suspend fun fetchLoginStatus(
        knownLoggedIn: Boolean = false,
        preferredMethod: WifiLoginMethod? = null
    ): LoginStatus
}

interface CampusAuthentication : LoginStatusReader {
    suspend fun performLogin(
        username: String,
        password: String,
        preferredMethod: WifiLoginMethod? = null
    ): LoginResult
}
