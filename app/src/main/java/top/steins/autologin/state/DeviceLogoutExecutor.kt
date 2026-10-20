package top.steins.autologin.state

import top.steins.autologin.HomeUiState
import top.steins.autologin.domain.model.AccountDevice
import top.steins.autologin.domain.model.DeviceLogoutResult
import top.steins.autologin.domain.port.AccountSessionGateway
import top.steins.autologin.domain.port.NetworkMonitor

/** 设备下线的准入和提交前校验；不持有可变状态，也不创建任务或锁。 */
internal class DeviceLogoutExecutor(
    private val network: NetworkMonitor,
    private val selfService: AccountSessionGateway
) {
    fun prepare(device: AccountDevice, state: HomeUiState): DeviceLogoutRequest? {
        val overview = state.accountOverview ?: return null
        if (!state.isOnline || !state.canLogoutDevices || state.isLoggingOutDevice ||
            state.isLoggingIn || device !in overview.devices
        ) return null
        return DeviceLogoutRequest(device, overview.username, state.wifiName, state.ipAddress)
    }

    /** 调用方取得会话执行权后传入最新状态，过期请求不会发送到服务端。 */
    suspend fun execute(request: DeviceLogoutRequest, current: HomeUiState): DeviceLogoutOutcome {
        val info = network.fetchCurrentNetworkInfo(current.hasLocationPermission)
        if (!current.isOnline || current.accountOverview?.username != request.username ||
            current.wifiName != request.wifiName || current.ipAddress != request.ipAddress ||
            !info.isWifi || info.wifiName != request.wifiName || info.ipAddress != request.ipAddress
        ) return DeviceLogoutOutcome.Skipped
        val device = request.device
        return DeviceLogoutOutcome.Completed(
            selfService.logoutDevice(device.sessionId, device.ipAddress, device.macAddress)
        )
    }
}

internal data class DeviceLogoutRequest(
    val device: AccountDevice,
    val username: String,
    val wifiName: String,
    val ipAddress: String
)

internal sealed interface DeviceLogoutOutcome {
    data object Skipped : DeviceLogoutOutcome
    data class Completed(val result: DeviceLogoutResult) : DeviceLogoutOutcome
}
