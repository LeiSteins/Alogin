package top.steins.autologin.network

const val SEAMLESS_DEVICE_LIMIT = 3

enum class SeamlessStatus { Enabled, Disabled, Unknown }

data class BoundDevice(
    val macAddress: String,
    val isOnline: Boolean?,
    val terminalInfo: String,
    val lastLoginTime: String,
    val lastLoginIp: String
)

data class BoundDeviceList(val total: Int, val devices: List<BoundDevice>)

data class SeamlessInfo(
    val username: String,
    val sessionGeneration: Long,
    val status: SeamlessStatus = SeamlessStatus.Unknown,
    val deviceList: BoundDeviceList? = null,
    val statusError: String = "",
    val devicesError: String = ""
)

sealed interface SeamlessLoadResult {
    data class Success(val info: SeamlessInfo) : SeamlessLoadResult
    data class Failure(val message: String) : SeamlessLoadResult
}

sealed interface SeamlessAction {
    data class Toggle(val expectedStatus: SeamlessStatus) : SeamlessAction
    data class Bind(val macAddress: String, val allowReplacement: Boolean = false) : SeamlessAction
    data class Unbind(val macAddress: String) : SeamlessAction
}

sealed interface SeamlessActionResult {
    data object Success : SeamlessActionResult
    data class Failure(val message: String) : SeamlessActionResult
    data class Indeterminate(val message: String) : SeamlessActionResult
}
