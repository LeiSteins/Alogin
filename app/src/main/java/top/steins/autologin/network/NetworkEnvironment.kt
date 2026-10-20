package top.steins.autologin.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.Flow
import top.steins.autologin.R
import top.steins.autologin.domain.model.CampusNetworkChange
import top.steins.autologin.domain.model.CurrentNetworkInfo
import top.steins.autologin.domain.model.LoginResult
import top.steins.autologin.domain.model.LoginStatus
import top.steins.autologin.domain.model.WifiLoginMethod
import top.steins.autologin.domain.port.CampusAuthentication
import top.steins.autologin.domain.port.InternetConnectivity
import top.steins.autologin.domain.port.NetworkMonitor

/** 仅用于应用组装和资源释放；功能层按用途接收窄接口。 */
interface NetworkEnvironment : NetworkMonitor, InternetConnectivity, CampusAuthentication, AutoCloseable {
    override fun close() = Unit
}

class DefaultNetworkEnvironment internal constructor(
    context: Context,
    private val campusNetwork: CampusNetworkProvider,
    logSink: HttpLogSink
) : NetworkEnvironment {

    private val appContext = context.applicationContext
    private val loginClients = CampusHttpClientCache { network ->
        createLoginClient(appContext, network, logSink)
    }

    override fun observeCampusNetworkChanges(): Flow<CampusNetworkChange> =
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

    override suspend fun fetchLoginStatus(
        knownLoggedIn: Boolean,
        preferredMethod: WifiLoginMethod?
    ): LoginStatus {
        val route = campusNetwork.awaitRoute(requireDns = false)
            ?: return LoginStatus(
                isLoggedIn = false,
                error = appContext.getString(R.string.campus_direct_network_unavailable)
            )
        return checkLoginStatus(
            appContext,
            loginClients.get(route.network),
            knownLoggedIn = knownLoggedIn,
            wlanUserIp = route.ipv4Address,
            preferredMethod = preferredMethod
        )
    }

    override suspend fun performLogin(
        username: String,
        password: String,
        preferredMethod: WifiLoginMethod?
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
            wlanUserIp = route.ipv4Address,
            preferredMethod = preferredMethod
        )
    }

    override fun close() {
        loginClients.close()
        campusNetwork.close()
    }
}

internal data class NetworkRefreshSnapshot(
    val ipAddress: String,
    val ssid: String?,
    val isSsidReadable: Boolean = true
)

internal fun detectCampusNetworkChange(
    previous: NetworkRefreshSnapshot?,
    current: NetworkRefreshSnapshot
): CampusNetworkChange? {
    if (previous == null) return CampusNetworkChange.INITIAL

    val ipAddressChanged = previous.ipAddress != current.ipAddress
    val ssidChanged = previous.isSsidReadable && current.isSsidReadable &&
            previous.ssid != current.ssid
    return when {
        ipAddressChanged && ssidChanged -> CampusNetworkChange.IP_ADDRESS_AND_SSID_CHANGED
        ipAddressChanged -> CampusNetworkChange.IP_ADDRESS_CHANGED
        ssidChanged -> CampusNetworkChange.SSID_CHANGED
        else -> null
    }
}
