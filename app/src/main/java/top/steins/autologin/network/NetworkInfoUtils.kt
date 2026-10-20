package top.steins.autologin.network

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.steins.autologin.R
import top.steins.autologin.domain.model.CurrentNetworkInfo
import top.steins.autologin.domain.model.WifiScanOutcome
import top.steins.autologin.domain.model.WifiScanResult
import top.steins.autologin.platform.hasWifiLocationPermission
import top.steins.autologin.platform.hasWifiScanPermission

private fun disconnectedNetworkInfo(context: Context) = CurrentNetworkInfo(
    wifiName = context.getString(R.string.network_not_connected),
    ipAddress = context.getString(R.string.network_value_none),
    isWifi = false,
    isCellular = false,
    isConnected = false
)

/**
 * 优先读取指定的非 VPN 物理 Wi-Fi，避免把 TUN/Fake-IP 虚拟网卡地址当作校园网 IP。
 * 没有可用的物理 Wi-Fi 时才回退到默认网络，用于识别蜂窝网络和断网状态。
 */
fun getCurrentNetworkInfo(
    context: Context,
    canReadWifiName: Boolean = hasWifiLocationPermission(context),
    physicalWifiNetwork: Network? = null
): CurrentNetworkInfo {
    val connectivityManager = context.applicationContext.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager
    val wifiCapabilities = physicalWifiNetwork
        ?.let(connectivityManager::getNetworkCapabilities)
        ?.takeIf { it.isPhysicalWifi() }
    val network = if (wifiCapabilities != null) {
        physicalWifiNetwork
    } else {
        connectivityManager.activeNetwork
    }
        ?: return disconnectedNetworkInfo(context)
    val capabilities = wifiCapabilities ?: connectivityManager.getNetworkCapabilities(network)
    val isWifi = capabilities.isPhysicalWifi()
    val isCellular = !isWifi &&
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    val isVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) != true
    val ipAddress = if (isVpn) {
        null
    } else {
        connectivityManager.getLinkProperties(network)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
        ?: context.getString(R.string.network_value_none)

    return CurrentNetworkInfo(
        wifiName = when {
            isWifi && !canReadWifiName ->
                context.getString(R.string.network_location_permission_required)

            isWifi -> readWifiSsid(context, capabilities)
            isCellular -> context.getString(R.string.network_cellular)
            else -> context.getString(R.string.network_non_wifi)
        },
        ipAddress = ipAddress,
        isWifi = isWifi,
        isCellular = isCellular,
        isConnected = true
    )
}

internal fun NetworkCapabilities?.isPhysicalWifi(): Boolean =
    this?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun readWifiSsid(context: Context, capabilities: NetworkCapabilities?): String {
    return try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            selectWifiSsid(
                transportSsid = (capabilities?.transportInfo as? WifiInfo)?.ssid,
                wifiManagerSsid = wifiManager.connectionInfo.ssid
            )
        } else {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo.ssid
        }
        ssid.normalizeSsid(context)
    } catch (_: SecurityException) {
        context.getString(R.string.network_location_permission_required)
    } catch (_: Exception) {
        context.getString(R.string.network_info_fetch_failed)
    }
}

@Suppress("DEPRECATION")
suspend fun scanNearbyWifi(context: Context): WifiScanOutcome = withContext(Dispatchers.Main.immediate) {
    if (!hasWifiScanPermission(context)) return@withContext WifiScanOutcome.PermissionDenied

    val locationManager = context.applicationContext.getSystemService(
        Context.LOCATION_SERVICE
    ) as LocationManager
    if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
        return@withContext WifiScanOutcome.LocationDisabled
    }

    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    if (!wifiManager.isWifiEnabled) return@withContext WifiScanOutcome.WifiDisabled

    val isFreshResult = try {
        awaitWifiScanResult(context.applicationContext, wifiManager)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SecurityException) {
        return@withContext WifiScanOutcome.PermissionDenied
    } catch (error: Exception) {
        return@withContext WifiScanOutcome.Failure(
            context.getString(R.string.wifi_scan_failed)
        )
    }

    val connectedSsid = readWifiSsid(context, capabilities = null)
    val rawScanResults = try {
        wifiManager.scanResults.orEmpty()
    } catch (_: SecurityException) {
        return@withContext WifiScanOutcome.PermissionDenied
    }
    val results = rawScanResults
        .asSequence()
        .filter { it.SSID.isNotBlank() && it.SSID != UNKNOWN_SSID }
        .groupBy { it.SSID }
        .map { (ssid, scans) ->
            WifiScanResult(
                ssid = ssid,
                strength = scans.maxOf { it.level },
                isConnected = ssid == connectedSsid
            )
        }
        .sortedWith(
            compareByDescending<WifiScanResult> { it.isConnected }
                .thenByDescending { it.strength }
        )
        .toList()

    if (results.isEmpty()) WifiScanOutcome.NoResults
    else WifiScanOutcome.Success(results, isFreshResult)
}

@SuppressLint("MissingPermission", "Deprecated")
@Suppress("DEPRECATION")
private suspend fun awaitWifiScanResult(context: Context, wifiManager: WifiManager): Boolean {
    return withTimeoutOrNull(WIFI_SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            var receiverRegistered = false
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
                    unregisterReceiver(context, this, receiverRegistered)
                    receiverRegistered = false
                    if (continuation.isActive) {
                        continuation.resume(
                            intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                        )
                    }
                }
            }

            fun completeWithCachedResults() {
                unregisterReceiver(context, receiver, receiverRegistered)
                receiverRegistered = false
                if (continuation.isActive) continuation.resume(false)
            }

            try {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
                if (!wifiManager.startScan()) completeWithCachedResults()
            } catch (error: Exception) {
                unregisterReceiver(context, receiver, receiverRegistered)
                receiverRegistered = false
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }

            continuation.invokeOnCancellation {
                unregisterReceiver(context, receiver, receiverRegistered)
                receiverRegistered = false
            }
        }
    } ?: false
}

private fun unregisterReceiver(context: Context, receiver: BroadcastReceiver, isRegistered: Boolean) {
    if (!isRegistered) return
    runCatching { context.unregisterReceiver(receiver) }
}

/**
 * Android 12+ 可能会在 [NetworkCapabilities.transportInfo] 中返回已脱敏的 SSID。
 * 该值仍非空，不能只依赖 Elvis 运算符回退到 [WifiManager.connectionInfo]。
 */
internal fun selectWifiSsid(transportSsid: String?, wifiManagerSsid: String?): String? {
    val ssid = transportSsid.takeIf { it.isReadableSsid() } ?: wifiManagerSsid
    return ssid?.trim('"')
}

private fun String?.isReadableSsid(): Boolean {
    val value = this?.trim('"').orEmpty()
    return value.isNotBlank() && value != UNKNOWN_SSID
}

private fun String?.normalizeSsid(context: Context): String {
    val value = this?.trim('"').orEmpty()
    return when {
        value.isBlank() -> context.getString(R.string.network_not_connected)
        value == UNKNOWN_SSID -> context.getString(R.string.network_ssid_unknown)
        else -> value
    }
}

private const val UNKNOWN_SSID = "<unknown ssid>"
private const val WIFI_SCAN_TIMEOUT_MS = 10_000L
