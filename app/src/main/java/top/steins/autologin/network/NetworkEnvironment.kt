package top.steins.autologin.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.Flow
import top.steins.autologin.R

/**
 * 网络状态的入口抽象，把 ViewModel 与系统 API 解耦，便于单元测试注入替身。
 */
interface NetworkEnvironment : AutoCloseable {
    fun observeDefaultNetworkChanges(): Flow<DefaultNetworkChange>

    fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo

    suspend fun awaitCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo =
        fetchCurrentNetworkInfo(canReadWifiName)

    fun hasValidatedInternet(): Boolean

    /** knownLoggedIn 仅用于认证接口明确返回当前 IP 已在线后的账号信息刷新。 */
    suspend fun fetchLoginStatus(knownLoggedIn: Boolean = false): LoginStatus

    suspend fun performLogin(
        username: String,
        password: String
    ): LoginResult

    override fun close() = Unit
}

class DefaultNetworkEnvironment internal constructor(
    context: Context,
    private val campusNetwork: CampusNetworkProvider = DefaultCampusNetworkProvider(context)
) : NetworkEnvironment {

    private val appContext = context.applicationContext
    private val loginClients = CampusHttpClientCache { network ->
        createLoginClient(appContext, network)
    }

    override fun observeDefaultNetworkChanges(): Flow<DefaultNetworkChange> =
        campusNetwork.observeChanges()

    override fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo =
        campusNetwork.currentNetworkInfo(canReadWifiName)

    override suspend fun awaitCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo {
        val current = campusNetwork.currentNetworkInfo(canReadWifiName)
        if (current.isWifi || current.isCellular || !current.isConnected) return current
        campusNetwork.awaitRoute(requireDns = false)
        return campusNetwork.currentNetworkInfo(canReadWifiName)
    }

    override fun hasValidatedInternet(): Boolean {
        val connectivityManager = appContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override suspend fun fetchLoginStatus(knownLoggedIn: Boolean): LoginStatus {
        val route = campusNetwork.awaitRoute(requireDns = false)
            ?: return LoginStatus(
                isLoggedIn = false,
                error = appContext.getString(R.string.campus_direct_network_unavailable)
            )
        return checkLoginStatus(
            appContext,
            loginClients.get(route.network),
            knownLoggedIn = knownLoggedIn,
            wlanUserIp = route.ipv4Address
        )
    }

    override suspend fun performLogin(
        username: String,
        password: String
    ): LoginResult {
        val route = campusNetwork.awaitRoute(requireDns = true)
            ?: return LoginResult.NetworkError(
                appContext.getString(R.string.campus_direct_network_unavailable)
            )
        return login(
            context = appContext,
            client = loginClients.get(route.network),
            username = username,
            password = password,
            wlanUserIp = route.ipv4Address
        )
    }

    override fun close() {
        loginClients.close()
        campusNetwork.close()
    }
}

enum class DefaultNetworkChange {
    INITIAL,
    IP_ADDRESS_CHANGED,
    SSID_CHANGED,
    IP_ADDRESS_AND_SSID_CHANGED
}

internal data class NetworkRefreshSnapshot(
    val ipAddress: String,
    val ssid: String?,
    val isSsidReadable: Boolean = true
)

internal fun detectDefaultNetworkChange(
    previous: NetworkRefreshSnapshot?,
    current: NetworkRefreshSnapshot
): DefaultNetworkChange? {
    if (previous == null) return DefaultNetworkChange.INITIAL

    val ipAddressChanged = previous.ipAddress != current.ipAddress
    val ssidChanged = previous.isSsidReadable && current.isSsidReadable &&
            previous.ssid != current.ssid
    return when {
        ipAddressChanged && ssidChanged -> DefaultNetworkChange.IP_ADDRESS_AND_SSID_CHANGED
        ipAddressChanged -> DefaultNetworkChange.IP_ADDRESS_CHANGED
        ssidChanged -> DefaultNetworkChange.SSID_CHANGED
        else -> null
    }
}
