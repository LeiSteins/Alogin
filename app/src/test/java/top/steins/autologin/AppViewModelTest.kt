package top.steins.autologin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import top.steins.autologin.data.AppearanceMode
import top.steins.autologin.data.CredentialSaveResult
import top.steins.autologin.data.SettingsGateway
import top.steins.autologin.data.TargetWifiConfigChange
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.AccountOverviewResult
import top.steins.autologin.network.CurrentNetworkInfo
import top.steins.autologin.network.DefaultNetworkChange
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.network.HttpLogEntry
import top.steins.autologin.network.HttpLogStorage
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.LoginStatus
import top.steins.autologin.network.NetworkEnvironment
import top.steins.autologin.network.SelfServiceGateway
import top.steins.autologin.network.update.UpdateDownloadResult
import top.steins.autologin.network.update.UpdateGateway
import top.steins.autologin.network.update.UpdateInfo
import top.steins.autologin.network.update.UpdateState

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settings = FakeSettingsGateway()
    private val selfService = FakeSelfServiceGateway()
    private val updates = FakeUpdateGateway()
    private val network = FakeNetworkEnvironment()

    @After
    fun resetHttpLogStorage() {
        HttpLogStorage.setEnabled(false)
    }

    private fun createViewModel(
        hasLocationPermission: () -> Boolean = { true },
        currentTimeMillis: () -> Long = { 1_000_000L }
    ): AppViewModel = AppViewModel(
        strings = AppStrings { resId, _ -> "msg:$resId" },
        settings = settings,
        selfService = selfService,
        updates = updates,
        network = network,
        hasLocationPermission = hasLocationPermission,
        currentTimeMillis = currentTimeMillis
    )

    @Test
    fun refreshStatus_publishesNetworkAndAccountOverviewForLoggedInTargetWifi() {
        network.info = CurrentNetworkInfo(
            wifiName = "bjut_wifi",
            ipAddress = "10.1.2.3",
            isWifi = true,
            isCellular = false,
            isConnected = true
        )
        network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
        val overview = AccountOverview("2021001", "100", "900", "20", emptyList())
        selfService.overviewResult = AccountOverviewResult.Success(overview)
        val viewModel = createViewModel()

        viewModel.refreshStatus()

        val state = viewModel.uiState.value
        assertEquals("bjut_wifi", state.wifiName)
        assertEquals("10.1.2.3", state.ipAddress)
        assertTrue(state.isOnline)
        assertEquals(overview, state.accountOverview)
    }

    @Test
    fun defaultNetworkChange_retriesRetryableAccountFailure() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
            val overview = AccountOverview("2021001", "100", "900", "20", emptyList())
            selfService.overviewResults.add(
                AccountOverviewResult.Failure("timeout", isRetryable = true)
            )
            selfService.overviewResults.add(AccountOverviewResult.Success(overview))
            val viewModel = createViewModel()

            network.emitDefaultNetworkChange(DefaultNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(2, selfService.overviewLoadCount)
            assertEquals(overview, viewModel.uiState.value.accountOverview)
            assertEquals("", viewModel.uiState.value.accountInfoError)
        }

    @Test
    fun defaultNetworkChange_doesNotRetryNonRetryableAccountFailure() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
            selfService.overviewResult = AccountOverviewResult.Failure(
                "invalid response",
                isRetryable = false
            )
            val viewModel = createViewModel()

            network.emitDefaultNetworkChange(DefaultNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(1, selfService.overviewLoadCount)
            assertEquals("invalid response", viewModel.uiState.value.accountInfoError)
        }

    @Test
    fun defaultNetworkChange_showsRetryableFailureAfterAttemptsAreExhausted() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
            selfService.overviewResult = AccountOverviewResult.Failure(
                "timeout",
                isRetryable = true
            )
            val viewModel = createViewModel()

            network.emitDefaultNetworkChange(DefaultNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(AppViewModel.NETWORK_REFRESH_ATTEMPTS, selfService.overviewLoadCount)
            assertFalse(viewModel.uiState.value.isAccountInfoLoading)
            assertEquals("timeout", viewModel.uiState.value.accountInfoError)
        }

    @Test
    fun defaultNetworkChange_doesNotRetryConfirmedLoggedOutStatus() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginStatus = LoginStatus(isLoggedIn = false)
            val viewModel = createViewModel()

            network.emitDefaultNetworkChange(DefaultNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(1, network.loginStatusFetchCount)
            assertFalse(viewModel.uiState.value.isOnline)
            assertEquals("", viewModel.uiState.value.networkStatusError)
        }

    @Test
    fun refreshStatus_clearsSessionAndAccountStateForNonTargetWifi() {
        network.info = CurrentNetworkInfo(
            wifiName = "other_wifi",
            ipAddress = "10.1.2.3",
            isWifi = true,
            isCellular = false,
            isConnected = true
        )
        val viewModel = createViewModel()

        viewModel.refreshStatus()

        val state = viewModel.uiState.value
        assertFalse(state.isOnline)
        assertNull(state.accountOverview)
        assertTrue(selfService.cleared)
    }

    @Test
    fun login_returnsFailureWhenCredentialsMissing() = runTest {
        val viewModel = createViewModel()

        val result = viewModel.login()

        assertEquals(
            LoginResult.Failure("msg:${R.string.login_no_credentials}"),
            result
        )
    }

    @Test
    fun login_returnsFailureOnNonTargetWifi() = runTest {
        settings.setCredentials("2021001", "secret")
        network.info = CurrentNetworkInfo(
            wifiName = "other_wifi",
            ipAddress = "10.1.2.3",
            isWifi = true,
            isCellular = false,
            isConnected = true
        )
        val viewModel = createViewModel()

        val result = viewModel.login()

        assertEquals(
            LoginResult.Failure("msg:${R.string.login_wrong_network}"),
            result
        )
    }

    @Test
    fun login_delegatesToNetworkOnTargetWifi() = runTest {
        settings.setCredentials("2021001", "secret")
        network.info = CurrentNetworkInfo(
            wifiName = "bjut_wifi",
            ipAddress = "10.1.2.3",
            isWifi = true,
            isCellular = false,
            isConnected = true
        )
        network.loginResult = LoginResult.Success
        val viewModel = createViewModel()

        val result = viewModel.login()

        assertEquals(LoginResult.Success, result)
        assertEquals(
            "2021001" to "secret",
            network.performedLogin
        )
    }

    @Test
    fun login_cancelsPendingRefreshRetriesAndStartsImmediately() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            settings.setCredentials("2021001", "secret")
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginStatus = LoginStatus(isLoggedIn = false, error = "timeout")
            network.loginResult = LoginResult.Success
            val viewModel = createViewModel()

            viewModel.onAppForegrounded()
            advanceTimeBy(AppViewModel.FOREGROUND_REFRESH_INITIAL_DELAY_MS)
            runCurrent()
            assertEquals(1, network.loginStatusFetchCount)

            val loginStartedAt = testScheduler.currentTime
            assertEquals(LoginResult.Success, viewModel.login())

            assertEquals(loginStartedAt, testScheduler.currentTime)
            assertEquals(
                "2021001" to "secret",
                network.performedLogin
            )
            advanceUntilIdle()
            assertEquals(1, network.loginStatusFetchCount)
        }

    @Test
    fun foregroundRefreshDelays_doNotHoldAccountOperationMutex() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginStatus = LoginStatus(isLoggedIn = false, error = "timeout")
            val viewModel = createViewModel()
            selfService.logoutResult = DeviceLogoutResult.Success

            viewModel.onAppForegrounded()
            val initialDelayStartedAt = testScheduler.currentTime

            assertEquals(
                DeviceLogoutResult.Success,
                viewModel.logoutDevice("session-1", "10.1.2.3", "AABBCCDDEEFF")
            )
            assertEquals(
                Triple("session-1", "10.1.2.3", "AABBCCDDEEFF"),
                selfService.logoutRequest
            )
            assertEquals(initialDelayStartedAt, testScheduler.currentTime)

            advanceTimeBy(AppViewModel.FOREGROUND_REFRESH_INITIAL_DELAY_MS)
            runCurrent()
            assertEquals(1, network.loginStatusFetchCount)
            val retryDelayStartedAt = testScheduler.currentTime

            assertEquals(
                DeviceLogoutResult.Success,
                viewModel.logoutDevice("session-1", "10.1.2.3", "AABBCCDDEEFF")
            )
            assertEquals(retryDelayStartedAt, testScheduler.currentTime)
            advanceUntilIdle()
        }

    @Test
    fun refreshAfterDeviceLogout_waitsTwoSecondsBeforeRefreshing() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
            selfService.overviewResult = AccountOverviewResult.Success(
                AccountOverview("2021001", "100", "900", "20", emptyList())
            )
            val viewModel = createViewModel()

            viewModel.refreshAfterDeviceLogout(successfulDeviceCount = 1)

            advanceTimeBy(AppViewModel.DEVICE_LOGOUT_REFRESH_DELAY_MS - 1L)
            runCurrent()
            assertEquals(0, network.loginStatusFetchCount)
            assertEquals(0, selfService.overviewLoadCount)

            advanceTimeBy(1L)
            runCurrent()
            assertEquals(1, network.loginStatusFetchCount)
            assertEquals(1, selfService.overviewLoadCount)
        }

    @Test
    fun initialization_doesNotCheckForUpdates() {
        createViewModel()

        assertEquals(0, updates.fetchCount)
    }

    @Test
    fun successfulLogin_checksForUpdatesAfterDelayWhenInternetIsValidated() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val now = 10_000_000L
            settings.setCredentials("2021001", "secret")
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginResult = LoginResult.Success
            network.validatedInternet = true
            updates.fetchResult = Result.success(
                UpdateInfo(
                    version = "0.2.0",
                    fileName = "alogin-v0.2.0.apk",
                    downloadUrl = "https://aloginupdate.steins.top/alogin-v0.2.0.apk",
                    versionCode = BuildConfig.VERSION_CODE + 1
                )
            )
            val viewModel = createViewModel(currentTimeMillis = { now })

            assertEquals(LoginResult.Success, viewModel.login())
            assertEquals(0, updates.fetchCount)

            advanceTimeBy(AppViewModel.AUTOMATIC_UPDATE_CHECK_DELAY_MS)
            runCurrent()

            assertEquals(1, updates.fetchCount)
            assertEquals(now, settings.lastUpdateCheckTimestamp)
        }

    @Test
    fun successfulLogin_doesNotCheckForUpdatesWithoutValidatedInternet() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            settings.setCredentials("2021001", "secret")
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginResult = LoginResult.Success
            network.validatedInternet = false
            val viewModel = createViewModel()

            assertEquals(LoginResult.Success, viewModel.login())
            advanceUntilIdle()

            assertEquals(0, updates.fetchCount)
            assertEquals(0L, settings.lastUpdateCheckTimestamp)
        }

    @Test
    fun successfulLogin_respectsAutomaticUpdateCheckInterval() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val now = 100_000_000L
            settings.lastUpdateCheckTimestamp =
                now - AppViewModel.AUTOMATIC_UPDATE_CHECK_INTERVAL_MS + 1L
            settings.setCredentials("2021001", "secret")
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginResult = LoginResult.Success
            network.validatedInternet = true
            val viewModel = createViewModel(currentTimeMillis = { now })

            assertEquals(LoginResult.Success, viewModel.login())
            advanceUntilIdle()

            assertEquals(0, updates.fetchCount)
        }

    @Test
    fun login_cancelsPreviouslyScheduledAutomaticUpdateCheck() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            settings.setCredentials("2021001", "secret")
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi",
                ipAddress = "10.1.2.3",
                isWifi = true,
                isCellular = false,
                isConnected = true
            )
            network.loginResult = LoginResult.Success
            network.validatedInternet = true
            updates.fetchResult = Result.success(
                UpdateInfo(
                    version = "0.2.0",
                    fileName = "alogin-v0.2.0.apk",
                    downloadUrl = "https://aloginupdate.steins.top/alogin-v0.2.0.apk",
                    versionCode = BuildConfig.VERSION_CODE + 1
                )
            )
            val viewModel = createViewModel()

            assertEquals(LoginResult.Success, viewModel.login())
            advanceTimeBy(AppViewModel.AUTOMATIC_UPDATE_CHECK_DELAY_MS - 1_000L)
            assertEquals(LoginResult.Success, viewModel.login())
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(0, updates.fetchCount)

            advanceTimeBy(AppViewModel.AUTOMATIC_UPDATE_CHECK_DELAY_MS - 1_000L)
            runCurrent()
            assertEquals(1, updates.fetchCount)
        }

    @Test
    fun checkForUpdates_publishesAvailableStateForNewerVersion() {
        val update = UpdateInfo(
            version = "0.2.0",
            fileName = "alogin-v0.2.0.apk",
            downloadUrl = "https://aloginupdate.steins.top/alogin-v0.2.0.apk",
            versionCode = BuildConfig.VERSION_CODE + 1,
            releaseNotes = "- 新增更新说明"
        )
        updates.fetchResult = Result.success(update)
        val viewModel = createViewModel()

        viewModel.checkForUpdates(manual = true)

        assertEquals(UpdateState.Available(update), viewModel.updateState.value)
    }

    @Test
    fun checkForUpdates_doesNotOfferVersionWithNonIncreasingVersionCode() {
        listOf(BuildConfig.VERSION_CODE, BuildConfig.VERSION_CODE - 1).forEach { code ->
            updates.fetchResult = Result.success(
                UpdateInfo(
                    version = "99.0.0",
                    fileName = "alogin-v99.0.0.apk",
                    downloadUrl = "https://aloginupdate.steins.top/alogin-v99.0.0.apk",
                    versionCode = code
                )
            )
            val viewModel = createViewModel()
            viewModel.checkForUpdates(manual = true)
            assertEquals(UpdateState.UpToDate("99.0.0"), viewModel.updateState.value)
        }
    }

    @Test
    fun saveCredentials_forwardsResultAndDoesNotStoreOnFailure() {
        settings.saveResult = CredentialSaveResult.ENCRYPTION_UNAVAILABLE
        val viewModel = createViewModel()

        assertEquals(
            CredentialSaveResult.ENCRYPTION_UNAVAILABLE,
            viewModel.saveCredentials("2021001", "secret")
        )
        assertNull(settings.saved)

        settings.saveResult = CredentialSaveResult.SAVED
        assertEquals(
            CredentialSaveResult.SAVED,
            viewModel.saveCredentials("2021001", "secret")
        )
        assertEquals("2021001" to "secret", settings.saved)
    }

    @Test
    fun aboutVersionClick_fifthClickEnablesHttpLog() {
        val viewModel = createViewModel()

        repeat(AppViewModel.HTTP_LOG_UNLOCK_TAP_COUNT - 1) {
            assertFalse(viewModel.onAboutVersionClicked())
        }
        assertFalse(viewModel.httpLogEnabled.value)

        assertTrue(viewModel.onAboutVersionClicked())
        assertTrue(viewModel.httpLogEnabled.value)
        assertTrue(settings.httpLogEnabled.value)
        assertTrue(HttpLogStorage.isEnabled)
    }

    @Test
    fun disableHttpLog_disablesStorageAndClearsEntries() {
        val viewModel = createViewModel()
        repeat(AppViewModel.HTTP_LOG_UNLOCK_TAP_COUNT) {
            viewModel.onAboutVersionClicked()
        }
        HttpLogStorage.add(
            HttpLogEntry(
                id = 1,
                method = "GET",
                url = "https://example.com",
                statusCode = 200,
                timestamp = 1,
                requestBody = "",
                responseBody = "",
                error = null
            )
        )

        viewModel.disableHttpLog()

        assertFalse(viewModel.httpLogEnabled.value)
        assertFalse(settings.httpLogEnabled.value)
        assertFalse(HttpLogStorage.isEnabled)
        assertTrue(HttpLogStorage.logs.value.isEmpty())
    }

    @Test
    fun onLocationPermissionChanged_updatesStateAndTriggersRefresh() {
        var granted = true
        val viewModel = createViewModel(hasLocationPermission = { granted })

        granted = false
        viewModel.onLocationPermissionChanged()

        assertFalse(viewModel.uiState.value.hasLocationPermission)
    }
}

private class FakeSettingsGateway : SettingsGateway {
    private val _targetWifis = MutableStateFlow(listOf("bjut_wifi"))
    override val targetWifis: StateFlow<List<String>> = _targetWifis.asStateFlow()

    private val _targetWifiConfigChanges = MutableSharedFlow<TargetWifiConfigChange>(
        extraBufferCapacity = 1
    )
    override val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange> =
        _targetWifiConfigChanges.asSharedFlow()

    private val _username = MutableStateFlow("")
    override val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    override val password: StateFlow<String> = _password.asStateFlow()

    private val _appearanceMode = MutableStateFlow(AppearanceMode.SYSTEM)
    override val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    private val _httpLogEnabled = MutableStateFlow(false)
    override val httpLogEnabled: StateFlow<Boolean> = _httpLogEnabled.asStateFlow()

    private val _credentialResetPending = MutableStateFlow(false)
    override val credentialResetPending: StateFlow<Boolean> = _credentialResetPending.asStateFlow()

    var saveResult = CredentialSaveResult.SAVED
    var saved: Pair<String, String>? = null
    var lastUpdateCheckTimestamp = 0L

    fun setCredentials(username: String, password: String) {
        _username.value = username
        _password.value = password
    }

    override fun addAutoDetectedTargetWifi(ssid: String): Boolean {
        if (!ssid.startsWith("bjut-sushe-")) return false
        if (ssid in _targetWifis.value) return false
        _targetWifis.value += ssid
        return true
    }

    override fun addTargetWifi(ssid: String) {
        if (ssid !in _targetWifis.value) _targetWifis.value += ssid
    }

    override fun removeTargetWifi(ssid: String) {
        _targetWifis.value = _targetWifis.value.filterNot { it == ssid }
    }

    override fun saveCredentials(username: String, password: String): CredentialSaveResult {
        if (saveResult == CredentialSaveResult.SAVED) {
            saved = username to password
        }
        return saveResult
    }

    override fun saveAppearanceMode(mode: AppearanceMode) {
        _appearanceMode.value = mode
    }

    override fun setHttpLogEnabled(enabled: Boolean) {
        _httpLogEnabled.value = enabled
    }

    override fun getLastUpdateCheckAt(): Long = lastUpdateCheckTimestamp

    override fun setLastUpdateCheckAt(timestampMillis: Long) {
        lastUpdateCheckTimestamp = timestampMillis
    }

    override fun acknowledgeCredentialReset() {
        _credentialResetPending.value = false
    }
}

private class FakeSelfServiceGateway : SelfServiceGateway {
    var overviewResult: AccountOverviewResult = AccountOverviewResult.Failure("fail")
    val overviewResults = ArrayDeque<AccountOverviewResult>()
    var overviewLoadCount = 0
    var logoutResult: DeviceLogoutResult = DeviceLogoutResult.Failure("fail")
    var logoutRequest: Triple<String, String, String>? = null
    var cleared = false

    override suspend fun loadAccountOverview(lgnUsername: String): AccountOverviewResult {
        overviewLoadCount += 1
        return overviewResults.removeFirstOrNull() ?: overviewResult
    }

    override suspend fun logoutDevice(
        sessionId: String,
        ipAddress: String,
        macAddress: String
    ): DeviceLogoutResult {
        logoutRequest = Triple(sessionId, ipAddress, macAddress)
        return logoutResult
    }

    override suspend fun clearSession() {
        cleared = true
    }
}

private class FakeNetworkEnvironment : NetworkEnvironment {
    private val defaultNetworkChanges = MutableSharedFlow<DefaultNetworkChange>(
        extraBufferCapacity = 1
    )
    var info = CurrentNetworkInfo(
        wifiName = "",
        ipAddress = "",
        isWifi = false,
        isCellular = false,
        isConnected = false
    )
    var loginStatus = LoginStatus(isLoggedIn = false)
    var loginResult: LoginResult = LoginResult.Failure("")
    var performedLogin: Pair<String, String>? = null
    var validatedInternet = false
    var loginStatusFetchCount = 0

    override fun observeDefaultNetworkChanges(): Flow<DefaultNetworkChange> = defaultNetworkChanges

    fun emitDefaultNetworkChange(change: DefaultNetworkChange) {
        check(defaultNetworkChanges.tryEmit(change))
    }

    override fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo = info

    override fun hasValidatedInternet(): Boolean = validatedInternet

    override suspend fun fetchLoginStatus(): LoginStatus {
        loginStatusFetchCount += 1
        return loginStatus
    }

    override suspend fun performLogin(
        username: String,
        password: String
    ): LoginResult {
        performedLogin = username to password
        return loginResult
    }
}

private class FakeUpdateGateway : UpdateGateway {
    var fetchResult: Result<UpdateInfo> = Result.failure(IllegalStateException("no update"))
    var downloadResult = UpdateDownloadResult.Failed
    var fetchCount = 0

    override suspend fun fetchLatestUpdate(currentVersion: String): UpdateInfo {
        fetchCount += 1
        return fetchResult.getOrThrow()
    }

    override fun downloadUpdate(update: UpdateInfo): UpdateDownloadResult = downloadResult
}
