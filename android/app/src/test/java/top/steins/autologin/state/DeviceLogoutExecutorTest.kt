package top.steins.autologin.state

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.steins.autologin.HomeUiState
import top.steins.autologin.domain.model.AccountDevice
import top.steins.autologin.domain.model.AccountOverview
import top.steins.autologin.domain.model.AccountOverviewResult
import top.steins.autologin.domain.model.CurrentNetworkInfo
import top.steins.autologin.domain.model.DeviceLogoutResult
import top.steins.autologin.domain.port.AccountSessionGateway
import top.steins.autologin.domain.port.NetworkMonitor

class DeviceLogoutExecutorTest {
    private val device = AccountDevice("session-1", "AA:BB:CC:11:22:33", "10.1.2.4")
    private val online = HomeUiState(
        wifiName = "campus", ipAddress = "10.1.2.3", isOnline = true,
        accountOverview = AccountOverview("account", "0", "0", "0", listOf(device)),
        canLogoutDevices = true
    )
    private var networkInfo = CurrentNetworkInfo("campus", "10.1.2.3", true, false, true)
    private val network = object : NetworkMonitor {
        override fun observeCampusNetworkChanges() = emptyFlow<top.steins.autologin.domain.model.CampusNetworkChange>()
        override fun fetchCurrentNetworkInfo(canReadWifiName: Boolean) = networkInfo
    }
    private val service = LogoutService()
    private val executor = DeviceLogoutExecutor(network, service)

    @Test
    fun prepare_rejectsForeignDeviceAndConflictingOperations() {
        assertNull(executor.prepare(device.copy(sessionId = "foreign"), online))
        assertNull(executor.prepare(device, online.copy(isLoggingIn = true)))
        assertNull(executor.prepare(device, online.copy(isLoggingOutDevice = true)))
        assertNull(executor.prepare(device, online.copy(canLogoutDevices = false)))
    }

    @Test
    fun queuedRequest_accountOrNetworkChanges_doNotSubmit() = runTest {
        val request = executor.prepare(device, online)!!
        for (current in listOf(
            online.copy(accountOverview = online.accountOverview!!.copy(username = "other")),
            online.copy(ipAddress = "10.1.2.99"),
            online.copy(wifiName = "other"),
            online.copy(isOnline = false)
        )) {
            assertEquals(DeviceLogoutOutcome.Skipped, executor.execute(request, current))
        }
        assertEquals(0, service.requests.size)
    }

    @Test
    fun physicalNetworkChangesBeforeUiRefresh_doNotSubmit() = runTest {
        val request = executor.prepare(device, online)!!
        networkInfo = networkInfo.copy(ipAddress = "10.1.2.99")

        assertEquals(DeviceLogoutOutcome.Skipped, executor.execute(request, online))
        assertEquals(0, service.requests.size)
    }

    @Test
    fun indeterminateResponse_isPreservedAndRequestIsSentOnce() = runTest {
        val request = executor.prepare(device, online)!!

        val outcome = executor.execute(request, online.copy(isLoggingOutDevice = true))

        assertEquals(DeviceLogoutOutcome.Completed(service.result), outcome)
        assertEquals(listOf(Triple(device.sessionId, device.ipAddress, device.macAddress)), service.requests)
    }
}

private class LogoutService : AccountSessionGateway {
    val requests = mutableListOf<Triple<String, String, String>>()
    val result = DeviceLogoutResult.Indeterminate("response unavailable")
    override suspend fun loadAccountOverview(lgnUsername: String): AccountOverviewResult = error("Unexpected overview request")
    override suspend fun clearSession() = error("Unexpected session reset")
    override suspend fun logoutDevice(sessionId: String, ipAddress: String, macAddress: String): DeviceLogoutResult {
        requests += Triple(sessionId, ipAddress, macAddress)
        return result
    }
}
