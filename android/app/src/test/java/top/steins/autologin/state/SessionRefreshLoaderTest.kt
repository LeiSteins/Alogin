package top.steins.autologin.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.steins.autologin.domain.model.AccountOverviewResult
import top.steins.autologin.domain.model.CampusNetworkChange
import top.steins.autologin.domain.model.CurrentNetworkInfo
import top.steins.autologin.domain.model.DeviceLogoutResult
import top.steins.autologin.domain.model.LoginStatus
import top.steins.autologin.domain.model.TargetWifiConfigChange
import top.steins.autologin.domain.model.WifiLoginMethod
import top.steins.autologin.domain.port.AccountSessionGateway
import top.steins.autologin.domain.port.LoginStatusReader
import top.steins.autologin.domain.port.NetworkMonitor
import top.steins.autologin.domain.port.WifiConfigStore

class SessionRefreshLoaderTest {
    private var info = CurrentNetworkInfo("campus", "10.1.2.3", true, false, true)
    private var status = LoginStatus(isLoggedIn = true, uid = "portal-account")
    private var afterAuthentication: () -> Unit = {}
    private var authenticationCount = 0
    private val network = object : NetworkMonitor {
        override fun observeCampusNetworkChanges() = emptyFlow<CampusNetworkChange>()
        override fun fetchCurrentNetworkInfo(canReadWifiName: Boolean) = info
    }
    private val authentication = object : LoginStatusReader {
        override suspend fun fetchLoginStatus(knownLoggedIn: Boolean, preferredMethod: WifiLoginMethod?): LoginStatus {
            authenticationCount++
            afterAuthentication()
            return status
        }
    }
    private val wifi = RefreshWifiConfig()
    private val service = RefreshAccountService()
    private val loader = SessionRefreshLoader(network, authentication, wifi, service) { null }

    @Test
    fun overviewFailure_stillPublishesPortalIdentityBeforeLoadingAccount() = runTest {
        val progress = mutableListOf<SessionRefreshProgress>()
        service.beforeOverview = {
            assertEquals(SessionRefreshProgress.Authenticated(info, "portal-account"), progress.last())
        }

        val result = loader.load(true, false, false, progress::add)

        assertEquals(SessionRefreshResult.AccountLoaded(service.overviewResult), result)
        assertEquals(listOf("portal-account"), service.requestedAccounts)
        assertEquals(0, service.clearCount)
    }

    @Test
    fun authenticationError_preservesSessionAndDoesNotLoadOverview() = runTest {
        status = LoginStatus(isLoggedIn = false, error = "timeout")
        val progress = mutableListOf<SessionRefreshProgress>()

        assertEquals(SessionRefreshResult.AuthenticationUnavailable(info, "timeout"), loader.load(true, false, false, progress::add))
        assertEquals(0, service.clearCount)
        assertTrue(service.requestedAccounts.isEmpty())
        assertTrue(SessionRefreshProgress.IdentityCleared !in progress)
    }

    @Test
    fun networkChangesDuringAuthentication_doesNotSaveLearnedLoginMethod() = runTest {
        status = status.copy(verifiedMethod = WifiLoginMethod.WLGN)
        afterAuthentication = { info = info.copy(ipAddress = "10.1.2.99") }

        loader.load(true, false, false) {}

        assertTrue(wifi.savedMethods.isEmpty())
    }

    @Test
    fun nonTargetNetwork_clearsSessionWithoutAuthenticationOrAccountRequests() = runTest {
        info = info.copy(wifiName = "unrelated")
        val progress = mutableListOf<SessionRefreshProgress>()

        assertEquals(SessionRefreshResult.OutsideTargetNetwork(info), loader.load(true, false, false, progress::add))
        assertEquals(0, authenticationCount)
        assertTrue(service.requestedAccounts.isEmpty())
        assertEquals(1, service.clearCount)
        assertTrue(SessionRefreshProgress.OutsideTargetNetwork(info) in progress)
    }
}

private class RefreshAccountService : AccountSessionGateway {
    val overviewResult = AccountOverviewResult.Failure("dashboard unavailable", isRetryable = true)
    val requestedAccounts = mutableListOf<String>()
    var clearCount = 0
    var beforeOverview: () -> Unit = {}
    override suspend fun loadAccountOverview(lgnUsername: String): AccountOverviewResult {
        beforeOverview()
        requestedAccounts += lgnUsername
        return overviewResult
    }
    override suspend fun clearSession() { clearCount++ }
    override suspend fun logoutDevice(sessionId: String, ipAddress: String, macAddress: String): DeviceLogoutResult =
        error("Unexpected device logout")
}

private class RefreshWifiConfig : WifiConfigStore {
    override val targetSsids = MutableStateFlow(listOf("campus"))
    override val targetWifiConfigChanges = MutableSharedFlow<TargetWifiConfigChange>()
    val savedMethods = mutableListOf<Pair<String, WifiLoginMethod>>()
    override fun addAutoDetectedTargetWifi(ssid: String) = false
    override fun addTargetWifi(ssid: String) { targetSsids.value += ssid }
    override fun removeTargetWifi(ssid: String) { targetSsids.value -= ssid }
    override fun getWifiLoginMethod(ssid: String): WifiLoginMethod? = null
    override fun saveWifiLoginMethod(ssid: String, method: WifiLoginMethod) { savedMethods += ssid to method }
}
