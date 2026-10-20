package top.steins.autologin.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import top.steins.autologin.domain.model.CampusNetworkChange
import top.steins.autologin.domain.model.CurrentNetworkInfo

internal data class CampusRoute(
    val network: Network,
    val ipv4Address: String
)

/**
 * 持续跟踪非 VPN 的物理 Wi-Fi，并为校园网请求提供稳定的 Network 快照。
 *
 * 不要求 NET_CAPABILITY_VALIDATED：校园网在认证前通常尚未通过系统联网验证。
 */
internal interface CampusNetworkProvider : AutoCloseable {
    fun observeChanges(): Flow<CampusNetworkChange>

    fun currentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo

    fun currentRoute(requireDns: Boolean = false): CampusRoute?

    suspend fun awaitRoute(requireDns: Boolean): CampusRoute?

    fun isCurrent(network: Network): Boolean

    override fun close() = Unit
}

internal class DefaultCampusNetworkProvider(context: Context) : CampusNetworkProvider {

    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager
    private val stateLock = Any()
    private val availableWifiNetworks = linkedSetOf<Network>()
    private val stateVersion = MutableStateFlow(0L)
    private val networkChanges = MutableSharedFlow<CampusNetworkChange>(
        replay = 1,
        extraBufferCapacity = 4
    )

    @Volatile
    private var physicalWifiNetwork: Network? = null

    private var previousSnapshot: NetworkRefreshSnapshot? = null
    private var callbackRegistered = false

    private val physicalWifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(stateLock) {
                availableWifiNetworks += network
                physicalWifiNetwork = network
            }
            notifyNetworkStateChanged()
        }

        override fun onLost(network: Network) {
            val selectedNetworkChanged = synchronized(stateLock) {
                availableWifiNetworks -= network
                if (physicalWifiNetwork == network) {
                    physicalWifiNetwork = availableWifiNetworks.lastOrNull()
                    true
                } else {
                    false
                }
            }
            if (selectedNetworkChanged) notifyNetworkStateChanged()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == physicalWifiNetwork) notifyNetworkStateChanged()
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties
        ) {
            if (network == physicalWifiNetwork) notifyNetworkStateChanged()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        callbackRegistered = runCatching {
            connectivityManager.registerNetworkCallback(request, physicalWifiCallback)
        }.isSuccess

        // 无 Wi-Fi 时不会收到 onAvailable，仍需要一次初始状态以更新界面。
        notifyNetworkStateChanged()
    }

    override fun observeChanges(): Flow<CampusNetworkChange> = networkChanges.asSharedFlow()

    override fun currentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo =
        getCurrentNetworkInfo(appContext, canReadWifiName, physicalWifiNetwork)

    override fun currentRoute(requireDns: Boolean): CampusRoute? {
        val network = physicalWifiNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (!capabilities.isPhysicalWifi()) return null

        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        if (requireDns && linkProperties.dnsServers.isEmpty()) return null
        val ipv4Address = linkProperties.linkAddresses
            .asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
            ?.takeIf(String::isUsableIpv4)
            ?: return null

        return CampusRoute(network = network, ipv4Address = ipv4Address)
    }

    override suspend fun awaitRoute(requireDns: Boolean): CampusRoute? {
        currentRoute(requireDns)?.let { return it }
        return withTimeoutOrNull(CAMPUS_ROUTE_READY_TIMEOUT_MS) {
            stateVersion
                .mapNotNull { currentRoute(requireDns) }
                .first()
        }
    }

    override fun isCurrent(network: Network): Boolean =
        physicalWifiNetwork == network && currentRoute(requireDns = false)?.network == network

    override fun close() {
        if (!callbackRegistered) return
        callbackRegistered = false
        runCatching { connectivityManager.unregisterNetworkCallback(physicalWifiCallback) }
    }

    private fun notifyNetworkStateChanged() {
        val canReadWifiName = hasWifiLocationPermission(appContext)
        val networkInfo = runCatching {
            currentNetworkInfo(canReadWifiName)
        }.getOrNull() ?: return
        val currentSnapshot = NetworkRefreshSnapshot(
            ipAddress = networkInfo.ipAddress,
            ssid = networkInfo.wifiName.takeIf { networkInfo.isWifi && canReadWifiName },
            isSsidReadable = canReadWifiName
        )

        val change = synchronized(stateLock) {
            detectCampusNetworkChange(previousSnapshot, currentSnapshot).also {
                previousSnapshot = currentSnapshot
                stateVersion.value += 1
            }
        }
        change?.let(networkChanges::tryEmit)
    }
}

private const val CAMPUS_ROUTE_READY_TIMEOUT_MS = 2_500L
