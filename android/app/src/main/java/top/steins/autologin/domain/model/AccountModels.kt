package top.steins.autologin.domain.model

data class AccountDevice(
    val sessionId: String,
    val macAddress: String,
    val ipAddress: String,
    val ipv6Address: String = "",
    val loginTime: String = "",
    val useTimeSeconds: String = "",
    val downFlow: String = "",
    val upFlow: String = "",
    val hostName: String = "",
    val terminalType: String = ""
)

data class AccountOverview(
    val username: String,
    val usedTrafficMegabytes: String,
    val remainingTrafficMegabytes: String,
    val remainingMoneyYuan: String,
    val devices: List<AccountDevice>
)

sealed interface AccountOverviewResult {
    data class Success(
        val overview: AccountOverview,
        val warningMessage: String = "",
        val isDeviceListAvailable: Boolean = true,
        val canLogoutDevices: Boolean = true
    ) : AccountOverviewResult
    data class Failure(
        val message: String,
        val isRetryable: Boolean = false
    ) : AccountOverviewResult
}

sealed interface DeviceLogoutResult {
    data object Success : DeviceLogoutResult
    data class Failure(val message: String) : DeviceLogoutResult
    data class Indeterminate(val message: String) : DeviceLogoutResult
}
