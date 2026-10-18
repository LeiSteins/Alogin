package top.steins.autologin

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
import top.steins.autologin.network.AccountDevice
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
import top.steins.autologin.network.update.UpdateDownloadState
import top.steins.autologin.network.update.UpdateDownloadFailure
import top.steins.autologin.network.update.UpdateTransfer
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
    private val newerVersion = "${BuildConfig.VERSION_NAME.substringBefore('.').toInt() + 1}.0.0"

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

    private fun availableUpdate() = UpdateInfo(
        version = "99.0.0", versionCode = 990,
        fileName = "alogin-v99.0.0.apk",
        downloadUrl = "https://aloginupdate.steins.top/alogin-v99.0.0.apk",
        releaseNotes = "更新测试"
    )

    private fun createOnlineViewModel(): AppViewModel {
        network.info = CurrentNetworkInfo(
            wifiName = "bjut_wifi",
            ipAddress = "10.1.2.3",
            isWifi = true,
            isCellular = false,
            isConnected = true
        )
        network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
        selfService.overviewResult = AccountOverviewResult.Success(
            AccountOverview(
                "2021001", "100", "900", "20",
                listOf(AccountDevice("session-1", "00:11:22:33:44:55", "10.1.2.3"))
            )
        )
        return createViewModel().also { it.refreshStatus() }
    }

    private fun createStartupViewModel(enabled: Boolean = true): AppViewModel {
        settings.setAutoLoginOnStartup(enabled)
        settings.setCredentials("2021001", "secret")
        network.info = CurrentNetworkInfo(
            wifiName = "bjut_wifi", ipAddress = "10.1.2.3",
            isWifi = true, isCellular = false, isConnected = true
        )
        return createViewModel()
    }

    @Test
    fun login_alreadyOnlineLoadsCurrentAccountAndRefreshesHome() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel(enabled = false)
            network.loginResult = LoginResult.AlreadyOnline
            network.knownOnlineLoginStatus = LoginStatus(isLoggedIn = true, uid = "2021002")
            val overview = AccountOverview("2021002", "100", "900", "20", emptyList())
            selfService.overviewResult = AccountOverviewResult.Success(overview)

            assertEquals(LoginResult.AlreadyOnline, viewModel.login())
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertEquals(listOf(true), network.loginStatusKnownOnlineRequests)
            assertEquals("2021002", selfService.lastRequestedUsername)
            assertTrue(viewModel.uiState.value.isOnline)
            assertEquals(overview, viewModel.uiState.value.accountOverview)
            assertFalse(viewModel.uiState.value.isAccountInfoLoading)
            assertEquals(
                "msg:${R.string.home_already_online_fetching}", viewModel.uiState.value.loginMessage
            )

            // 已在线信息只用于本次登录确认，后续刷新仍通过登录页核验。
            viewModel.refreshStatus()
            advanceUntilIdle()
            assertEquals(listOf(true, false), network.loginStatusKnownOnlineRequests)
            assertFalse(viewModel.uiState.value.isOnline)
            assertEquals(1, network.loginCount)
        }

    @Test
    fun login_alreadyOnlineKeepsOnlineStateWhenAccountCannotBeDetermined() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel(enabled = false)
            network.loginResult = LoginResult.AlreadyOnline

            viewModel.login()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isOnline)
            assertNull(viewModel.uiState.value.accountOverview)
            assertEquals("msg:${R.string.account_info_fetch_failed}", viewModel.uiState.value.accountInfoError)
            assertEquals(0, selfService.overviewLoadCount)
            assertEquals(1, network.loginCount)
        }

    @Test
    fun startupAutoLogin_alreadyOnlineResponseLoadsAccountWithoutAnotherLogin() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginResult = LoginResult.AlreadyOnline
            network.knownOnlineLoginStatus = LoginStatus(isLoggedIn = true, uid = "2021002")
            val overview = AccountOverview("2021002", "100", "900", "20", emptyList())
            selfService.overviewResult = AccountOverviewResult.Success(overview)

            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertEquals(listOf(false, true), network.loginStatusKnownOnlineRequests)
            assertEquals("2021002", selfService.lastRequestedUsername)
            assertTrue(viewModel.uiState.value.isOnline)
            assertEquals(overview, viewModel.uiState.value.accountOverview)
            assertEquals("", viewModel.uiState.value.networkStatusError)
        }

    @Test
    fun login_callerCancellationKeepsSingleTaskAndConfirmsStatus() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val gate = CompletableDeferred<Unit>()
            network.loginGate = gate
            network.loginResult = LoginResult.Success
            network.loginStatusAfterLogin = LoginStatus(isLoggedIn = true, uid = "2021001")
            val overview = AccountOverview("2021001", "100", "900", "20", emptyList())
            selfService.overviewResult = AccountOverviewResult.Success(overview)
            val viewModel = createStartupViewModel(enabled = false)
            val caller = launch { viewModel.login() }
            runCurrent()
            assertTrue(viewModel.uiState.value.isLoggingIn)
            viewModel.requestLogin()
            assertEquals(1, network.loginCount)

            caller.cancelAndJoin()
            assertTrue(viewModel.uiState.value.isLoggingIn)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertFalse(viewModel.uiState.value.isLoggingIn)
            assertTrue(viewModel.uiState.value.isOnline)
            assertEquals(overview, viewModel.uiState.value.accountOverview)
            assertEquals("msg:${R.string.home_login_success_fetching}", viewModel.uiState.value.loginMessage)
            viewModel.consumeLoginMessage()
            assertNull(viewModel.uiState.value.loginMessage)
        }

    @Test
    fun startupAutoLogin_waitsForCredentialInitialization() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val gate = CompletableDeferred<Unit>()
            settings.initializationGate = gate
            val viewModel = createStartupViewModel()
            network.loginResult = LoginResult.Failure("bad password")
            viewModel.onAppForegrounded()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(0, network.loginCount)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, network.loginCount)
            assertFalse(viewModel.uiState.value.isLoggingIn)
        }

    @Test
    fun login_viewModelClearedCancelsTaskAndClearsLoading() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.loginGate = CompletableDeferred()
            val viewModel = createStartupViewModel(enabled = false)
            val store = ViewModelStore()
            store.put("app", viewModel)
            viewModel.requestLogin()
            runCurrent()
            assertTrue(viewModel.uiState.value.isLoggingIn)
            store.clear()
            runCurrent()
            assertFalse(viewModel.uiState.value.isLoggingIn)
            assertNull(viewModel.uiState.value.loginMessage)
        }

    @Test
    fun download_idleReadyAndFailedStatesDoNotPoll() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createViewModel()
            val terminalStates = listOf(
                null,
                UpdateTransfer(availableUpdate(), UpdateDownloadState.Ready),
                UpdateTransfer(availableUpdate(), UpdateDownloadState.Failed(UpdateDownloadFailure.DOWNLOAD))
            )
            for (transfer in terminalStates) {
                updates.transfer = transfer
                val before = updates.refreshCount
                viewModel.setUpdateForeground(true)
                runCurrent()
                assertEquals(before + 1, updates.refreshCount)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(before + 1, updates.refreshCount)
                viewModel.setUpdateForeground(false)
            }
        }

    @Test
    fun download_pollsOnlyWhileDownloadingInForegroundAndStopsAtCompletion() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            updates.transfer = UpdateTransfer(availableUpdate(), UpdateDownloadState.Downloading())
            val viewModel = createViewModel()
            viewModel.setUpdateForeground(true)
            runCurrent()
            assertEquals(1, updates.refreshCount)
            advanceTimeBy(AppUpdateCoordinator.DOWNLOAD_PROGRESS_INTERVAL_MS)
            runCurrent()
            assertEquals(2, updates.refreshCount)
            viewModel.setUpdateForeground(false)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, updates.refreshCount)

            viewModel.setUpdateForeground(true)
            runCurrent()
            assertEquals(3, updates.refreshCount)
            // 未收到完成广播时，也能通过下一次轮询发现完成。
            updates.transfer = UpdateTransfer(availableUpdate(), UpdateDownloadState.Ready)
            advanceTimeBy(AppUpdateCoordinator.DOWNLOAD_PROGRESS_INTERVAL_MS)
            runCurrent()
            assertEquals(4, updates.refreshCount)
            assertEquals(UpdateDownloadState.Ready, (viewModel.updateState.value as UpdateState.Available).download)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(4, updates.refreshCount)
            viewModel.setUpdateForeground(false)
        }

    @Test
    fun updateFailure_retriesWithBackoffAndManualCheckBypassesIt() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            var now = 10_000_000L
            settings.lastUpdateCheckTimestamp = now - 1_000
            settings.setCredentials("2021001", "secret")
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi", ipAddress = "10.1.2.3",
                isWifi = true, isCellular = false, isConnected = true
            )
            val timedViewModel = createViewModel(currentTimeMillis = { now })
            timedViewModel.checkForUpdates(manual = true)
            assertEquals(now - 1_000, settings.lastUpdateCheckTimestamp)
            assertEquals(now + AppUpdateCoordinator.INITIAL_CHECK_RETRY_DELAY_MS, settings.updateCheckRetryTimestamp)

            network.loginResult = LoginResult.Success
            network.validatedInternet = true
            timedViewModel.login()
            advanceUntilIdle()
            assertEquals(1, updates.fetchCount)
            now = settings.updateCheckRetryTimestamp
            timedViewModel.login()
            advanceUntilIdle()
            assertEquals(2, updates.fetchCount)
            assertEquals(now + 2 * AppUpdateCoordinator.INITIAL_CHECK_RETRY_DELAY_MS, settings.updateCheckRetryTimestamp)

            updates.fetchResult = Result.success(availableUpdate())
            timedViewModel.checkForUpdates(manual = true)
            assertEquals(3, updates.fetchCount)
            assertEquals(now, settings.lastUpdateCheckTimestamp)
            assertEquals(0L, settings.updateCheckRetryTimestamp)
        }

    @Test
    fun updateFailure_backoffIsBoundedAndSurvivesCoordinatorRecreation() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            var now = 10_000_000L
            settings.setCredentials("2021001", "secret")
            network.info = CurrentNetworkInfo(
                wifiName = "bjut_wifi", ipAddress = "10.1.2.3",
                isWifi = true, isCellular = false, isConnected = true
            )
            network.loginResult = LoginResult.Success
            network.validatedInternet = true
            val viewModel = createViewModel(currentTimeMillis = { now })
            var lastDelay = 0L
            repeat(8) {
                viewModel.checkForUpdates(manual = true)
                lastDelay = settings.updateCheckRetryTimestamp - now
                assertTrue(lastDelay <= AppUpdateCoordinator.MAXIMUM_CHECK_RETRY_DELAY_MS)
                now = settings.updateCheckRetryTimestamp
            }
            assertEquals(AppUpdateCoordinator.MAXIMUM_CHECK_RETRY_DELAY_MS, lastDelay)
            val before = updates.fetchCount
            val restored = createViewModel(currentTimeMillis = { now - 1 })
            restored.login()
            advanceUntilIdle()
            assertEquals(before, updates.fetchCount)
            now++
            val due = createViewModel(currentTimeMillis = { now })
            due.login()
            advanceUntilIdle()
            assertEquals(before + 1, updates.fetchCount)
        }

    @Test
    fun startupAutoLogin_disabledDoesNotSubmitCredentials() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel(enabled = false)
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_successConfirmsStatusAndLoadsAccountOverview() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginResult = LoginResult.Success
            network.loginStatusAfterLogin = LoginStatus(isLoggedIn = true, uid = "2021001")
            val overview = AccountOverview("2021001", "100", "900", "20", emptyList())
            selfService.overviewResult = AccountOverviewResult.Success(overview)

            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertEquals("2021001" to "secret", network.performedLogin)
            assertTrue(viewModel.uiState.value.isOnline)
            assertFalse(viewModel.uiState.value.isAccountInfoLoading)
            assertEquals(overview, viewModel.uiState.value.accountOverview)
        }

    @Test
    fun startupAutoLogin_failureDoesNotRepeatOnRefreshOrForegroundAndAllowsManualLogin() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginResult = LoginResult.Failure("bad password")
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertEquals("bad password", viewModel.uiState.value.networkStatusError)
            assertFalse(viewModel.uiState.value.isAccountInfoLoading)

            viewModel.refreshStatus()
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(1, network.loginCount)

            viewModel.login()
            assertEquals(2, network.loginCount)
        }

    @Test
    fun startupAutoLogin_networkErrorEndsLoadingWithoutRetryingLogin() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginResult = LoginResult.NetworkError("unreachable")
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertEquals("unreachable", viewModel.uiState.value.networkStatusError)
            assertFalse(viewModel.uiState.value.isAccountInfoLoading)
        }

    @Test
    fun startupAutoLogin_alreadyOnlineDoesNotLoginLaterWhenOffline() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            network.loginStatus = LoginStatus(isLoggedIn = false)
            viewModel.refreshStatus()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_missingCredentialsDoesNotLoginWhenCredentialsAreAddedLater() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            settings.setCredentials("", "")
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            settings.setCredentials("2021001", "secret")
            viewModel.refreshStatus()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_nonTargetWifiDoesNotLoginAfterSwitchingToTargetWifi() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.info = network.info.copy(wifiName = "other_wifi")
            viewModel.onAppForegrounded()
            advanceUntilIdle()

            network.info = network.info.copy(wifiName = "bjut_wifi")
            network.emitDefaultNetworkChange(DefaultNetworkChange.SSID_CHANGED)
            advanceUntilIdle()
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_cellularNetworkDoesNotSubmitCredentials() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.info = network.info.copy(isWifi = false, isCellular = true)
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_waitsForLocationPermissionResult() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            settings.setAutoLoginOnStartup(true)
            settings.setCredentials("2021001", "secret")
            network.info = CurrentNetworkInfo(
                wifiName = "unknown", ipAddress = "10.1.2.3",
                isWifi = true, isCellular = false, isConnected = true
            )
            var granted = false
            val viewModel = createViewModel(hasLocationPermission = { granted })
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)

            granted = true
            network.info = network.info.copy(wifiName = "bjut_wifi")
            viewModel.onLocationPermissionChanged()
            advanceUntilIdle()
            assertEquals(1, network.loginCount)
        }

    @Test
    fun startupAutoLogin_statusErrorIsRetriedBeforeAttemptingLogin() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginStatus = LoginStatus(isLoggedIn = false, error = "not ready")
            viewModel.onAppForegrounded()
            advanceTimeBy(AppViewModel.FOREGROUND_REFRESH_INITIAL_DELAY_MS)
            runCurrent()
            assertEquals(0, network.loginCount)

            network.loginStatus = LoginStatus(isLoggedIn = false)
            advanceUntilIdle()
            assertEquals(1, network.loginCount)
        }

    @Test
    fun startupAutoLogin_exhaustedStatusChecksDoNotLoginOnLaterForeground() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginStatus = LoginStatus(isLoggedIn = false, error = "unreachable")
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(AppViewModel.FOREGROUND_REFRESH_ATTEMPTS, network.loginStatusFetchCount)

            network.loginStatus = LoginStatus(isLoggedIn = false)
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_changingSettingAffectsNextStartupOnly() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel(enabled = false)
            viewModel.setAutoLoginOnStartup(true)
            assertTrue(settings.autoLoginOnStartup.value)
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)

            val restartedViewModel = createViewModel()
            restartedViewModel.onAppForegrounded()
            restartedViewModel.setAutoLoginOnStartup(false)
            advanceUntilIdle()
            assertFalse(settings.autoLoginOnStartup.value)
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_manualLoginConsumesPendingAttempt() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            viewModel.onAppForegrounded()
            viewModel.login()
            viewModel.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(1, network.loginCount)
        }

    @Test
    fun download_repeatedClicksAndChecksDoNotReplaceActiveTask() {
        updates.fetchResult = Result.success(availableUpdate())
        val viewModel = createViewModel()
        viewModel.setUpdateForeground(true)
        viewModel.checkForUpdates(true)
        viewModel.downloadAvailableUpdate()
        viewModel.downloadAvailableUpdate()
        viewModel.checkForUpdates(true)

        assertEquals(1, updates.downloadCount)
        assertEquals(1, updates.fetchCount)
        assertEquals(UpdateDownloadState.Downloading(), (viewModel.updateState.value as UpdateState.Available).download)
        viewModel.setUpdateForeground(false)
    }

    @Test
    fun download_completedInForegroundRequestsInstallationOnlyOnce() {
        updates.fetchResult = Result.success(availableUpdate())
        val viewModel = createViewModel()
        viewModel.setUpdateForeground(true)
        viewModel.checkForUpdates(true)
        viewModel.downloadAvailableUpdate()
        updates.publish(UpdateDownloadState.Ready)

        assertEquals(availableUpdate(), viewModel.updateInstallation.value)
        viewModel.consumeUpdateInstallation()
        updates.publish(UpdateDownloadState.Ready)
        assertNull(viewModel.updateInstallation.value)
        // 从安装器取消返回后，可手动安装同一个包，不再下载。
        viewModel.setUpdateForeground(false)
        viewModel.setUpdateForeground(true)
        assertNull(viewModel.updateInstallation.value)
        viewModel.downloadAvailableUpdate()
        assertEquals(availableUpdate(), viewModel.updateInstallation.value)
        assertEquals(1, updates.downloadCount)
        viewModel.setUpdateForeground(false)
    }

    @Test
    fun download_completedInBackgroundRestoresReadyWithoutLaunchingInstaller() {
        updates.fetchResult = Result.success(availableUpdate())
        val viewModel = createViewModel()
        viewModel.setUpdateForeground(true)
        viewModel.checkForUpdates(true)
        viewModel.downloadAvailableUpdate()
        viewModel.setUpdateForeground(false)
        updates.publish(UpdateDownloadState.Ready)
        viewModel.setUpdateForeground(true)

        assertEquals(UpdateState.Available(availableUpdate(), UpdateDownloadState.Ready), viewModel.updateState.value)
        assertNull(viewModel.updateInstallation.value)
        viewModel.setUpdateForeground(false)
    }

    @Test
    fun download_restoresAfterProcessRestartWithoutCheckingServer() {
        updates.transfer = UpdateTransfer(availableUpdate(), UpdateDownloadState.Downloading(42, true))
        val viewModel = createViewModel()
        viewModel.setUpdateForeground(true)
        assertEquals(UpdateState.Available(availableUpdate(), updates.transfer!!.state), viewModel.updateState.value)
        assertEquals(0, updates.fetchCount)
        assertEquals(0, updates.downloadCount)
        updates.publish(UpdateDownloadState.Ready)
        assertNull(viewModel.updateInstallation.value)
        viewModel.setUpdateForeground(false)
    }

    @Test
    fun download_dismissingDialogDisablesAutomaticInstallation() {
        updates.fetchResult = Result.success(availableUpdate())
        val viewModel = createViewModel()
        viewModel.setUpdateForeground(true)
        viewModel.checkForUpdates(true)
        viewModel.downloadAvailableUpdate()
        viewModel.cancelAutomaticUpdateInstall()
        updates.publish(UpdateDownloadState.Ready)
        assertNull(viewModel.updateInstallation.value)
        viewModel.setUpdateForeground(false)
    }

    @Test
    fun installation_permissionReturnRequiresSameVersionAndReadyFile() {
        updates.transfer = UpdateTransfer(availableUpdate(), UpdateDownloadState.Ready)
        val viewModel = createViewModel()
        viewModel.setUpdateForeground(true)
        viewModel.resumeUpdateInstallation(989)
        assertNull(viewModel.updateInstallation.value)
        viewModel.resumeUpdateInstallation(990)
        assertEquals(availableUpdate(), viewModel.updateInstallation.value)
        viewModel.consumeUpdateInstallation()
        updates.publish(UpdateDownloadState.Failed(UpdateDownloadFailure.MISSING_FILE))
        viewModel.resumeUpdateInstallation(990)
        assertNull(viewModel.updateInstallation.value)
        viewModel.setUpdateForeground(false)
    }

    @Test
    fun installation_revalidatesFileAndOffersRedownloadWhenMissing() = runTest {
        updates.transfer = UpdateTransfer(availableUpdate(), UpdateDownloadState.Ready)
        val viewModel = createViewModel()
        viewModel.setUpdateForeground(true)
        assertNull(viewModel.validatedUpdateDownload(availableUpdate()))
        assertEquals(
            UpdateDownloadState.Failed(UpdateDownloadFailure.INVALID_APK),
            (viewModel.updateState.value as UpdateState.Available).download
        )
        viewModel.downloadAvailableUpdate()
        assertEquals(1, updates.downloadCount)
        viewModel.setUpdateForeground(false)
    }

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
    fun refreshAccountInfo_keepsHomeContentUntilStatusAndOverviewArrive() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val original = viewModel.uiState.value
            val refreshedOverview = original.accountOverview!!.copy(usedFlowMb = "200")
            network.info = network.info.copy(wifiName = "bjut-sushe-1", ipAddress = "10.1.2.4")
            network.loginStatusGate = CompletableDeferred()
            selfService.overviewGate = CompletableDeferred()
            selfService.overviewResult = AccountOverviewResult.Success(refreshedOverview)

            viewModel.refreshAccountInfo()

            assertEquals(
                original.copy(isAccountInfoLoading = true, canLogoutDevices = false),
                viewModel.uiState.value
            )

            network.loginStatusGate!!.complete(Unit)
            runCurrent()

            assertEquals(
                original.copy(
                    wifiName = "bjut-sushe-1",
                    ipAddress = "10.1.2.4",
                    isAccountInfoLoading = true,
                    canLogoutDevices = false
                ),
                viewModel.uiState.value
            )

            selfService.overviewGate!!.complete(Unit)
            runCurrent()

            assertEquals(refreshedOverview, viewModel.uiState.value.accountOverview)
            assertFalse(viewModel.uiState.value.isAccountInfoLoading)
            assertTrue(viewModel.uiState.value.isDeviceListAvailable)
            assertTrue(viewModel.uiState.value.canLogoutDevices)
        }

    @Test
    fun refreshAccountInfo_clearsCardsOnlyAfterLogoutIsConfirmed() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val original = viewModel.uiState.value
            network.loginStatus = LoginStatus(isLoggedIn = false)
            network.loginStatusGate = CompletableDeferred()

            viewModel.refreshAccountInfo()

            assertEquals(
                original.copy(isAccountInfoLoading = true, canLogoutDevices = false),
                viewModel.uiState.value
            )

            network.loginStatusGate!!.complete(Unit)
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isOnline)
            assertNull(state.accountOverview)
            assertFalse(state.isAccountInfoLoading)
            assertFalse(state.isDeviceListAvailable)
            assertFalse(state.canLogoutDevices)
            assertTrue(selfService.cleared)
        }

    @Test
    fun refreshAccountInfo_keepsHomeContentWhenStatusRequestFails() {
        val viewModel = createOnlineViewModel()
        val original = viewModel.uiState.value
        network.loginStatus = LoginStatus(isLoggedIn = false, error = "timeout")

        viewModel.refreshAccountInfo()

        assertEquals(
            original.copy(accountInfoError = "timeout", canLogoutDevices = false),
            viewModel.uiState.value
        )
        assertEquals(1, selfService.overviewLoadCount)
        assertFalse(selfService.cleared)
    }

    @Test
    fun refreshAccountInfo_keepsExistingCardsWhenOverviewRequestFails() {
        val viewModel = createOnlineViewModel()
        val original = viewModel.uiState.value
        selfService.overviewResult = AccountOverviewResult.Failure("timeout")

        viewModel.refreshAccountInfo()

        assertEquals(
            original.copy(accountInfoError = "timeout", canLogoutDevices = false),
            viewModel.uiState.value
        )
    }

    @Test
    fun refreshAccountInfo_doesNotKeepPreviousAccountCardsAfterAccountChanges() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021002")
            selfService.overviewGate = CompletableDeferred()
            val refreshedOverview = viewModel.uiState.value.accountOverview!!.copy(
                username = "2021002",
                devices = emptyList()
            )
            selfService.overviewResult = AccountOverviewResult.Success(refreshedOverview)

            viewModel.refreshAccountInfo()

            val loadingState = viewModel.uiState.value
            assertTrue(loadingState.isOnline)
            assertTrue(loadingState.isAccountInfoLoading)
            assertNull(loadingState.accountOverview)
            assertFalse(loadingState.isDeviceListAvailable)
            assertFalse(loadingState.canLogoutDevices)

            selfService.overviewGate!!.complete(Unit)
            runCurrent()

            assertEquals(refreshedOverview, viewModel.uiState.value.accountOverview)
            assertFalse(viewModel.uiState.value.isAccountInfoLoading)
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
            // 登录成功后由 ViewModel 自动确认状态，替换已取消的后台刷新。
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
            advanceUntilIdle()
            assertEquals(2, network.loginStatusFetchCount)
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
                    version = newerVersion,
                    fileName = "alogin-v$newerVersion.apk",
                    downloadUrl = "https://aloginupdate.steins.top/alogin-v$newerVersion.apk",
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
                    version = newerVersion,
                    fileName = "alogin-v$newerVersion.apk",
                    downloadUrl = "https://aloginupdate.steins.top/alogin-v$newerVersion.apk",
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
            version = newerVersion,
            fileName = "alogin-v$newerVersion.apk",
            downloadUrl = "https://aloginupdate.steins.top/alogin-v$newerVersion.apk",
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
    fun selectedAccount_isUsedByNextLogin() = runTest {
        settings.accounts.value = listOf(
            top.steins.autologin.data.SavedAccount("alice", "a"),
            top.steins.autologin.data.SavedAccount("bob", "b")
        )
        network.info = CurrentNetworkInfo(
            wifiName = "bjut_wifi", ipAddress = "10.1.2.3",
            isWifi = true, isCellular = false, isConnected = true
        )
        val viewModel = createViewModel()
        assertEquals(CredentialSaveResult.SAVED, viewModel.selectAccount("bob"))
        viewModel.login()
        assertEquals("bob" to "b", network.performedLogin)
        viewModel.removeAccount("bob")
        viewModel.login()
        assertEquals("alice" to "a", network.performedLogin)
        viewModel.removeAccount("alice")
        assertEquals("", viewModel.username.value)
        assertEquals("", viewModel.password.value)
    }

    @Test
    fun saveCredentials_forwardsResultAndDoesNotStoreOnFailure() = runTest {
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
    override suspend fun readActiveAccount(): top.steins.autologin.data.SavedAccount? =
        username.value.takeIf { it.isNotBlank() }?.let {
            top.steins.autologin.data.SavedAccount(it, password.value)
        }
    override val autoLoginOnStartup = MutableStateFlow(false)

    override fun setAutoLoginOnStartup(enabled: Boolean) {
        autoLoginOnStartup.value = enabled
    }
    override val accounts = MutableStateFlow<List<top.steins.autologin.data.SavedAccount>>(emptyList())
    override suspend fun selectAccount(username: String): CredentialSaveResult {
        val account = accounts.value.find { it.username == username }
            ?: return CredentialSaveResult.INVALID_INPUT
        setCredentials(account.username, account.password)
        return CredentialSaveResult.SAVED
    }
    override suspend fun removeAccount(username: String): CredentialSaveResult {
        accounts.value = accounts.value.filterNot { it.username == username }
        if (this.username.value == username) {
            val next = accounts.value.firstOrNull()
            setCredentials(next?.username.orEmpty(), next?.password.orEmpty())
        }
        return CredentialSaveResult.SAVED
    }

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
    var updateCheckRetryTimestamp = 0L
    var initializationGate: CompletableDeferred<Unit>? = null

    override suspend fun initialize() {
        initializationGate?.await()
    }

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

    override suspend fun saveCredentials(username: String, password: String): CredentialSaveResult {
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

    override fun getUpdateCheckRetryAt(): Long = updateCheckRetryTimestamp

    override fun setUpdateCheckRetryAt(timestampMillis: Long) {
        updateCheckRetryTimestamp = timestampMillis
    }

    override fun acknowledgeCredentialReset() {
        _credentialResetPending.value = false
    }
}

private class FakeSelfServiceGateway : SelfServiceGateway {
    var overviewGate: CompletableDeferred<Unit>? = null
    var overviewResult: AccountOverviewResult = AccountOverviewResult.Failure("fail")
    val overviewResults = ArrayDeque<AccountOverviewResult>()
    var overviewLoadCount = 0
    var lastRequestedUsername: String? = null
    var logoutResult: DeviceLogoutResult = DeviceLogoutResult.Failure("fail")
    var logoutRequest: Triple<String, String, String>? = null
    var cleared = false

    override suspend fun loadAccountOverview(lgnUsername: String): AccountOverviewResult {
        overviewLoadCount += 1
        lastRequestedUsername = lgnUsername
        overviewGate?.await()
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
    var loginGate: CompletableDeferred<Unit>? = null
    var loginStatusGate: CompletableDeferred<Unit>? = null
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
    var knownOnlineLoginStatus = LoginStatus(isLoggedIn = true)
    val loginStatusKnownOnlineRequests = mutableListOf<Boolean>()
    var loginResult: LoginResult = LoginResult.Failure("")
    var performedLogin: Pair<String, String>? = null
    var loginCount = 0
    var loginStatusAfterLogin: LoginStatus? = null
    var validatedInternet = false
    var loginStatusFetchCount = 0

    override fun observeDefaultNetworkChanges(): Flow<DefaultNetworkChange> = defaultNetworkChanges

    fun emitDefaultNetworkChange(change: DefaultNetworkChange) {
        check(defaultNetworkChanges.tryEmit(change))
    }

    override fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo = info

    override fun hasValidatedInternet(): Boolean = validatedInternet

    override suspend fun fetchLoginStatus(knownLoggedIn: Boolean): LoginStatus {
        loginStatusFetchCount += 1
        loginStatusKnownOnlineRequests += knownLoggedIn
        loginStatusGate?.await()
        return if (knownLoggedIn) knownOnlineLoginStatus else loginStatus
    }

    override suspend fun performLogin(
        username: String,
        password: String
    ): LoginResult {
        loginCount += 1
        performedLogin = username to password
        loginGate?.await()
        loginStatusAfterLogin?.let { loginStatus = it }
        return loginResult
    }
}

private class FakeUpdateGateway : UpdateGateway {
    var fetchResult: Result<UpdateInfo> = Result.failure(IllegalStateException("no update"))
    var transfer: UpdateTransfer? = null
    var downloadCount = 0
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    var fetchCount = 0
    var refreshCount = 0

    override suspend fun fetchLatestUpdate(currentVersion: String): UpdateInfo {
        fetchCount += 1
        return fetchResult.getOrThrow()
    }

    override suspend fun downloadUpdate(update: UpdateInfo): UpdateTransfer {
        downloadCount++
        return UpdateTransfer(update, UpdateDownloadState.Downloading()).also { transfer = it }
    }

    fun publish(state: UpdateDownloadState) {
        transfer = transfer?.copy(state = state)
        changes.tryEmit(Unit)
    }

    override suspend fun refreshDownload(): UpdateTransfer? {
        refreshCount++
        return transfer
    }
    override fun observeDownloadChanges(): Flow<Unit> = changes
    override suspend fun validatedDownload(update: UpdateInfo): java.io.File? = null
}
