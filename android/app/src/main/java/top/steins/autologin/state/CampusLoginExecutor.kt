package top.steins.autologin.state

import top.steins.autologin.AppStrings
import top.steins.autologin.R
import top.steins.autologin.domain.model.LoginResult
import top.steins.autologin.domain.model.WifiLoginMethod
import top.steins.autologin.domain.port.AccountStore
import top.steins.autologin.domain.port.CampusAuthentication
import top.steins.autologin.domain.port.NetworkMonitor
import top.steins.autologin.domain.port.WifiConfigStore

/** 认证前置条件与执行；调用方负责生命周期和会话操作串行化。 */
internal class CampusLoginExecutor(
    private val strings: AppStrings,
    private val accounts: AccountStore,
    private val wifiConfig: WifiConfigStore,
    private val network: NetworkMonitor,
    private val authentication: CampusAuthentication
) {
    fun preferredMethod(ssid: String): WifiLoginMethod? =
        wifiConfig.getWifiLoginMethod(ssid)
            ?: WifiLoginMethod.WLGN.takeIf { ssid == "bjut_wifi" }

    suspend fun login(canReadWifiName: Boolean): LoginResult {
        val networkInfo = network.awaitCurrentNetworkInfo(canReadWifiName)
        if (networkInfo.isWifi) wifiConfig.addAutoDetectedTargetWifi(networkInfo.wifiName)
        // 读取同一份凭据快照，避免账号切换时拼接不同账号的用户名和密码。
        val account = accounts.readActiveAccount()
        val username = account?.username.orEmpty()
        val password = account?.password.orEmpty()
        return when {
            username.isBlank() || password.isBlank() ->
                LoginResult.Failure(strings.get(R.string.login_no_credentials))
            networkInfo.isCellular ->
                LoginResult.Failure(strings.get(R.string.login_cellular_blocked))
            !networkInfo.isWifi || networkInfo.wifiName !in wifiConfig.targetSsids.value ->
                LoginResult.Failure(strings.get(R.string.login_wrong_network))
            else -> authentication.performLogin(username, password, preferredMethod(networkInfo.wifiName))
        }
    }
}
