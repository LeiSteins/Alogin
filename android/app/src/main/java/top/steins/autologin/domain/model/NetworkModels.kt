package top.steins.autologin.domain.model

data class CurrentNetworkInfo(
    val wifiName: String,
    val ipAddress: String,
    val isWifi: Boolean,
    val isCellular: Boolean,
    val isConnected: Boolean
)

data class WifiScanResult(
    val ssid: String,
    val strength: Int,
    val isConnected: Boolean
)

sealed interface WifiScanOutcome {
    data class Success(
        val results: List<WifiScanResult>,
        val isFreshResult: Boolean
    ) : WifiScanOutcome

    data object PermissionDenied : WifiScanOutcome
    data object LocationDisabled : WifiScanOutcome
    data object WifiDisabled : WifiScanOutcome
    data object NoResults : WifiScanOutcome
    data class Failure(val message: String) : WifiScanOutcome
}
