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
import top.steins.autologin.data.AccountStore
import top.steins.autologin.data.AppPreferences
import top.steins.autologin.data.HttpLogPreferences
import top.steins.autologin.data.UpdateCheckStore
import top.steins.autologin.data.WifiConfigStore
import top.steins.autologin.data.TargetWifiConfigChange
import top.steins.autologin.data.WifiLoginMethod
import top.steins.autologin.network.AccountDevice
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.AccountOverviewResult
import top.steins.autologin.network.CurrentNetworkInfo
import top.steins.autologin.network.CampusNetworkChange
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.network.HttpLogEntry
import top.steins.autologin.network.HttpLogStorage
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.LoginStatus
import top.steins.autologin.network.NetworkEnvironment
import top.steins.autologin.network.SelfServiceGateway
import top.steins.autologin.network.SeamlessAction
import top.steins.autologin.network.SeamlessActionResult
import top.steins.autologin.network.SeamlessInfo
import top.steins.autologin.network.SeamlessLoadResult
import top.steins.autologin.network.SeamlessStatus
import top.steins.autologin.network.update.UpdateDownloadState
import top.steins.autologin.network.update.UpdateDownloadFailure
import top.steins.autologin.network.update.UpdateTransfer
import top.steins.autologin.network.update.UpdateGateway
import top.steins.autologin.network.update.UpdateInfo
import top.steins.autologin.state.CampusSessionCoordinator
import top.steins.autologin.state.HttpLogStateHolder
import top.steins.autologin.state.UpdateStateHolder
import top.steins.autologin.network.WifiScanOutcome
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

    private val settings = FakeSettingsStore()
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
        accountStore = settings,
        wifiConfig = settings,
        appPreferences = settings,
        logPreferences = settings,
        updateCheckStore = settings,
        selfService = selfService,
        updates = updates,
        network = network,
        hasLocationPermission = hasLocationPermission,
        currentTimeMillis = currentTimeMillis,
        scanWifi = { WifiScanOutcome.NoResults }
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
        return createViewModel().also { it.session.refreshStatus() }
    }

    @Test
    fun seamlessQuery_usesAuthenticatedAccountRatherThanSelectedSavedAccount() = runTest {
        settings.setCredentials("different-saved-account", "secret")
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        assertEquals("2021001", selfService.seamlessRequestedAccount)
        assertEquals(SeamlessStatus.Enabled, viewModel.session.seamlessState.value.info?.status)
    }

    @Test
    fun seamlessQueryFailure_doesNotHideAccountOverviewOrMarkNetworkOffline() = runTest {
        selfService.seamlessLoadResult = SeamlessLoadResult.Failure("list unavailable")
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.session.uiState.value.isOnline)
        assertEquals("2021001", viewModel.session.uiState.value.accountOverview?.username)
        assertEquals("list unavailable", viewModel.session.seamlessState.value.error)
        assertFalse(viewModel.session.seamlessState.value.canManage)
        assertEquals(SeamlessStatus.Unknown, viewModel.session.uiState.value.seamlessStatus)
    }

    @Test
    fun seamlessQuery_reentryKeepsPreviousStateUntilNewDataArrives() = runTest {
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        val previous = viewModel.session.seamlessState.value
        val previousLoads = selfService.seamlessLoadCount
        val gate = CompletableDeferred<Unit>()
        selfService.seamlessLoadGate = gate
        val nextInfo = SeamlessInfo("2021001", 2, SeamlessStatus.Disabled)
        selfService.seamlessLoadResult = SeamlessLoadResult.Success(nextInfo)

        viewModel.session.refreshSeamlessInfo()
        runCurrent()
        assertEquals(previous.copy(isLoading = true), viewModel.session.seamlessState.value)
        assertFalse(viewModel.session.seamlessState.value.canManage)
        assertEquals(previousLoads + 1, selfService.seamlessLoadCount)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(nextInfo, viewModel.session.seamlessState.value.info)
        assertFalse(viewModel.session.seamlessState.value.isLoading)
        assertTrue(viewModel.session.seamlessState.value.canManage)
    }

    @Test
    fun seamlessQuery_failureKeepsPreviousDataAndDisablesWritesUntilRecovery() = runTest {
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        val previousInfo = viewModel.session.seamlessState.value.info
        selfService.seamlessLoadResult = SeamlessLoadResult.Failure("list unavailable")

        viewModel.session.refreshSeamlessInfo()
        advanceUntilIdle()
        assertEquals(previousInfo, viewModel.session.seamlessState.value.info)
        assertEquals("list unavailable", viewModel.session.seamlessState.value.error)
        assertFalse(viewModel.session.seamlessState.value.canManage)

        val gate = CompletableDeferred<Unit>()
        selfService.seamlessLoadGate = gate
        selfService.seamlessLoadResult = SeamlessLoadResult.Success(previousInfo!!)
        viewModel.session.refreshSeamlessInfo()
        runCurrent()
        assertEquals(previousInfo, viewModel.session.seamlessState.value.info)
        assertEquals("list unavailable", viewModel.session.seamlessState.value.error)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("", viewModel.session.seamlessState.value.error)
        assertTrue(viewModel.session.seamlessState.value.canManage)
    }

    @Test
    fun seamlessHomeStatus_fullRefreshKeepsLastValueUntilNewStatusArrives() = runTest {
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        assertEquals(SeamlessStatus.Enabled, viewModel.session.uiState.value.seamlessStatus)
        val previousInfo = viewModel.session.seamlessState.value.info
        val overviewGate = CompletableDeferred<Unit>()
        val statusGate = CompletableDeferred<Unit>()
        selfService.overviewGate = overviewGate
        selfService.seamlessLoadGate = statusGate
        selfService.seamlessLoadResult = SeamlessLoadResult.Success(SeamlessInfo("2021001", 2, SeamlessStatus.Disabled))

        viewModel.session.refreshAccountInfo()
        runCurrent()
        assertEquals(SeamlessStatus.Enabled, viewModel.session.uiState.value.seamlessStatus)
        assertEquals(previousInfo, viewModel.session.seamlessState.value.info)
        assertTrue(viewModel.session.seamlessState.value.isLoading)
        overviewGate.complete(Unit)
        runCurrent()
        assertTrue(viewModel.session.seamlessState.value.isLoading)
        assertEquals(previousInfo, viewModel.session.seamlessState.value.info)
        assertEquals(SeamlessStatus.Enabled, viewModel.session.uiState.value.seamlessStatus)

        statusGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SeamlessStatus.Disabled, viewModel.session.uiState.value.seamlessStatus)
        assertEquals(SeamlessStatus.Disabled, viewModel.session.seamlessState.value.info?.status)
    }

    @Test
    fun seamlessHomeStatus_queryFailureOrUnknownStatusDoesNotReplaceConfirmedValue() = runTest {
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        for (result in listOf(
            SeamlessLoadResult.Failure("session unavailable"),
            SeamlessLoadResult.Success(SeamlessInfo("2021001", 2, statusError = "status unavailable"))
        )) {
            selfService.seamlessLoadResult = result
            viewModel.session.refreshAccountInfo()
            advanceUntilIdle()
            assertEquals(SeamlessStatus.Enabled, viewModel.session.uiState.value.seamlessStatus)
        }
    }

    @Test
    fun seamlessHomeStatus_portalQueryFailureKeepsPreviousAccountAndStatus() = runTest {
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        network.loginStatus = LoginStatus(isLoggedIn = false, error = "timeout")
        viewModel.session.refreshAccountInfo()
        advanceUntilIdle()
        assertEquals("2021001", viewModel.session.uiState.value.accountOverview?.username)
        assertEquals(SeamlessStatus.Enabled, viewModel.session.uiState.value.seamlessStatus)
    }

    @Test
    fun seamlessHomeStatus_accountChangeClearsPreviousValueBeforeFetchingNewAccount() = runTest {
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        selfService.overviewGate = gate
        network.loginStatus = LoginStatus(isLoggedIn = true, uid = "another-account")
        selfService.overviewResult = AccountOverviewResult.Success(AccountOverview("another-account", "0", "0", "0", emptyList()))
        selfService.seamlessLoadResult = SeamlessLoadResult.Success(SeamlessInfo("another-account", 2, SeamlessStatus.Disabled))
        viewModel.session.refreshAccountInfo()
        runCurrent()
        assertEquals(SeamlessStatus.Unknown, viewModel.session.uiState.value.seamlessStatus)
        assertNull(viewModel.session.seamlessState.value.info)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("another-account", viewModel.session.uiState.value.accountOverview?.username)
        assertEquals(SeamlessStatus.Disabled, viewModel.session.uiState.value.seamlessStatus)
    }

    @Test
    fun seamlessMutation_rejectsRepeatedClicksAndReloadsAfterCompletion() = runTest {
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        val previousLoads = selfService.seamlessLoadCount
        val gate = CompletableDeferred<Unit>()
        selfService.seamlessActionGate = gate
        val action = SeamlessAction.Bind("AABBCC112233", true)
        viewModel.session.performSeamlessAction(action)
        runCurrent()
        viewModel.session.performSeamlessAction(action)
        assertTrue(viewModel.session.seamlessState.value.isSubmitting)
        assertEquals(1, selfService.seamlessActionCount)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.session.seamlessState.value.isSubmitting)
        assertEquals(previousLoads + 1, selfService.seamlessLoadCount)
    }

    @Test
    fun seamlessMutation_doesNotWriteWhenPortalAccountChanged() = runTest {
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        network.loginStatus = LoginStatus(isLoggedIn = true, uid = "another-account")
        viewModel.session.performSeamlessAction(SeamlessAction.Toggle(SeamlessStatus.Enabled))
        advanceUntilIdle()
        assertEquals(0, selfService.seamlessActionCount)
        assertNull(viewModel.session.seamlessState.value.info)
        assertEquals(SeamlessStatus.Unknown, viewModel.session.uiState.value.seamlessStatus)
    }

    @Test
    fun seamlessToggle_refreshesHomeWhenServerDisconnectsTheCurrentDevice() = runTest {
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        selfService.onSeamlessAction = { network.loginStatus = LoginStatus(isLoggedIn = false) }
        viewModel.session.performSeamlessAction(SeamlessAction.Toggle(SeamlessStatus.Enabled))
        advanceUntilIdle()
        assertEquals(1, selfService.seamlessActionCount)
        assertFalse(viewModel.session.uiState.value.isOnline)
        assertNull(viewModel.session.uiState.value.accountOverview)
        assertNull(viewModel.session.seamlessState.value.info)
        assertEquals(SeamlessStatus.Unknown, viewModel.session.uiState.value.seamlessStatus)
    }

    @Test
    fun seamlessNetworkChange_clearsOldAccountEvenWhenQueryWasPending() = runTest {
        val viewModel = createOnlineViewModel()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        selfService.seamlessLoadGate = gate
        viewModel.session.refreshSeamlessInfo()
        runCurrent()
        network.info = network.info.copy(wifiName = "other-wifi")
        viewModel.session.refreshStatus()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.session.seamlessState.value.info)
        assertFalse(viewModel.session.seamlessState.value.canManage)
        assertEquals(SeamlessStatus.Unknown, viewModel.session.uiState.value.seamlessStatus)
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

            assertEquals(LoginResult.AlreadyOnline, viewModel.session.login())
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertEquals(listOf(true), network.loginStatusKnownOnlineRequests)
            assertEquals("2021002", selfService.lastRequestedUsername)
            assertTrue(viewModel.session.uiState.value.isOnline)
            assertEquals(overview, viewModel.session.uiState.value.accountOverview)
            assertFalse(viewModel.session.uiState.value.isAccountInfoLoading)
            assertEquals(
                "msg:${R.string.home_already_online_fetching}", viewModel.session.uiState.value.loginMessage
            )

            // 已在线信息只用于本次登录确认，后续刷新仍通过登录页核验。
            viewModel.session.refreshStatus()
            advanceUntilIdle()
            assertEquals(listOf(true, false), network.loginStatusKnownOnlineRequests)
            assertFalse(viewModel.session.uiState.value.isOnline)
            assertEquals(1, network.loginCount)
        }

    @Test
    fun login_alreadyOnlineKeepsOnlineStateWhenAccountCannotBeDetermined() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel(enabled = false)
            network.loginResult = LoginResult.AlreadyOnline

            viewModel.session.login()
            advanceUntilIdle()

            assertTrue(viewModel.session.uiState.value.isOnline)
            assertNull(viewModel.session.uiState.value.accountOverview)
            assertEquals("msg:${R.string.account_info_fetch_failed}", viewModel.session.uiState.value.accountInfoError)
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

            viewModel.session.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertEquals(listOf(false, true), network.loginStatusKnownOnlineRequests)
            assertEquals("2021002", selfService.lastRequestedUsername)
            assertTrue(viewModel.session.uiState.value.isOnline)
            assertEquals(overview, viewModel.session.uiState.value.accountOverview)
            assertEquals("", viewModel.session.uiState.value.networkStatusError)
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
            val caller = launch { viewModel.session.login() }
            runCurrent()
            assertTrue(viewModel.session.uiState.value.isLoggingIn)
            viewModel.session.requestLogin()
            assertEquals(1, network.loginCount)

            caller.cancelAndJoin()
            assertTrue(viewModel.session.uiState.value.isLoggingIn)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertFalse(viewModel.session.uiState.value.isLoggingIn)
            assertTrue(viewModel.session.uiState.value.isOnline)
            assertEquals(overview, viewModel.session.uiState.value.accountOverview)
            assertEquals("msg:${R.string.home_login_success_fetching}", viewModel.session.uiState.value.loginMessage)
            viewModel.session.consumeLoginMessage()
            assertNull(viewModel.session.uiState.value.loginMessage)
        }

    @Test
    fun startupAutoLogin_waitsForCredentialInitialization() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val gate = CompletableDeferred<Unit>()
            settings.initializationGate = gate
            val viewModel = createStartupViewModel()
            network.loginResult = LoginResult.Failure("bad password")
            viewModel.session.onAppForegrounded()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(0, network.loginCount)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, network.loginCount)
            assertFalse(viewModel.session.uiState.value.isLoggingIn)
        }

    @Test
    fun login_viewModelClearedCancelsTaskAndClearsLoading() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.loginGate = CompletableDeferred()
            val viewModel = createStartupViewModel(enabled = false)
            val store = ViewModelStore()
            store.put("app", viewModel)
            viewModel.session.requestLogin()
            runCurrent()
            assertTrue(viewModel.session.uiState.value.isLoggingIn)
            store.clear()
            runCurrent()
            assertFalse(viewModel.session.uiState.value.isLoggingIn)
            assertNull(viewModel.session.uiState.value.loginMessage)
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
                viewModel.update.setForeground(true)
                runCurrent()
                assertEquals(before + 1, updates.refreshCount)
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(before + 1, updates.refreshCount)
                viewModel.update.setForeground(false)
            }
        }

    @Test
    fun download_pollsOnlyWhileDownloadingInForegroundAndStopsAtCompletion() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            updates.transfer = UpdateTransfer(availableUpdate(), UpdateDownloadState.Downloading())
            val viewModel = createViewModel()
            viewModel.update.setForeground(true)
            runCurrent()
            assertEquals(1, updates.refreshCount)
            advanceTimeBy(UpdateStateHolder.DOWNLOAD_PROGRESS_INTERVAL_MS)
            runCurrent()
            assertEquals(2, updates.refreshCount)
            viewModel.update.setForeground(false)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, updates.refreshCount)

            viewModel.update.setForeground(true)
            runCurrent()
            assertEquals(3, updates.refreshCount)
            // 未收到完成广播时，也能通过下一次轮询发现完成。
            updates.transfer = UpdateTransfer(availableUpdate(), UpdateDownloadState.Ready)
            advanceTimeBy(UpdateStateHolder.DOWNLOAD_PROGRESS_INTERVAL_MS)
            runCurrent()
            assertEquals(4, updates.refreshCount)
            assertEquals(UpdateDownloadState.Ready, (viewModel.update.state.value as UpdateState.Available).download)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(4, updates.refreshCount)
            viewModel.update.setForeground(false)
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
            timedViewModel.update.check(manual = true)
            assertEquals(now - 1_000, settings.lastUpdateCheckTimestamp)
            assertEquals(now + UpdateStateHolder.INITIAL_CHECK_RETRY_DELAY_MS, settings.updateCheckRetryTimestamp)

            network.loginResult = LoginResult.Success
            network.validatedInternet = true
            timedViewModel.session.login()
            advanceUntilIdle()
            assertEquals(1, updates.fetchCount)
            now = settings.updateCheckRetryTimestamp
            timedViewModel.session.login()
            advanceUntilIdle()
            assertEquals(2, updates.fetchCount)
            assertEquals(now + 2 * UpdateStateHolder.INITIAL_CHECK_RETRY_DELAY_MS, settings.updateCheckRetryTimestamp)

            updates.fetchResult = Result.success(availableUpdate())
            timedViewModel.update.check(manual = true)
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
                viewModel.update.check(manual = true)
                lastDelay = settings.updateCheckRetryTimestamp - now
                assertTrue(lastDelay <= UpdateStateHolder.MAXIMUM_CHECK_RETRY_DELAY_MS)
                now = settings.updateCheckRetryTimestamp
            }
            assertEquals(UpdateStateHolder.MAXIMUM_CHECK_RETRY_DELAY_MS, lastDelay)
            val before = updates.fetchCount
            val restored = createViewModel(currentTimeMillis = { now - 1 })
            restored.session.login()
            advanceUntilIdle()
            assertEquals(before, updates.fetchCount)
            now++
            val due = createViewModel(currentTimeMillis = { now })
            due.session.login()
            advanceUntilIdle()
            assertEquals(before + 1, updates.fetchCount)
        }

    @Test
    fun startupAutoLogin_disabledDoesNotSubmitCredentials() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel(enabled = false)
            viewModel.session.onAppForegrounded()
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

            viewModel.session.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertEquals("2021001" to "secret", network.performedLogin)
            assertTrue(viewModel.session.uiState.value.isOnline)
            assertFalse(viewModel.session.uiState.value.isAccountInfoLoading)
            assertEquals(overview, viewModel.session.uiState.value.accountOverview)
        }

    @Test
    fun startupAutoLogin_failureDoesNotRepeatOnRefreshOrForegroundAndAllowsManualLogin() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginResult = LoginResult.Failure("bad password")
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertEquals("bad password", viewModel.session.uiState.value.networkStatusError)
            assertFalse(viewModel.session.uiState.value.isAccountInfoLoading)

            viewModel.session.refreshStatus()
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(1, network.loginCount)

            viewModel.session.login()
            assertEquals(2, network.loginCount)
        }

    @Test
    fun startupAutoLogin_networkErrorEndsLoadingWithoutRetryingLogin() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginResult = LoginResult.NetworkError("unreachable")
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(1, network.loginCount)
            assertEquals("unreachable", viewModel.session.uiState.value.networkStatusError)
            assertFalse(viewModel.session.uiState.value.isAccountInfoLoading)
        }

    @Test
    fun startupAutoLogin_alreadyOnlineDoesNotLoginLaterWhenOffline() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021001")
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()

            network.loginStatus = LoginStatus(isLoggedIn = false)
            viewModel.session.refreshStatus()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_missingCredentialsDoesNotLoginWhenCredentialsAreAddedLater() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            settings.setCredentials("", "")
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()

            settings.setCredentials("2021001", "secret")
            viewModel.session.refreshStatus()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_nonTargetWifiDoesNotLoginAfterSwitchingToTargetWifi() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.info = network.info.copy(wifiName = "other_wifi")
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()

            network.info = network.info.copy(wifiName = "bjut_wifi")
            network.emitCampusNetworkChange(CampusNetworkChange.SSID_CHANGED)
            advanceUntilIdle()
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_cellularNetworkDoesNotSubmitCredentials() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.info = network.info.copy(isWifi = false, isCellular = true)
            viewModel.session.onAppForegrounded()
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
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)

            granted = true
            network.info = network.info.copy(wifiName = "bjut_wifi")
            viewModel.session.onLocationPermissionChanged()
            advanceUntilIdle()
            assertEquals(1, network.loginCount)
        }

    @Test
    fun startupAutoLogin_statusErrorIsRetriedBeforeAttemptingLogin() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            network.loginStatus = LoginStatus(isLoggedIn = false, error = "not ready")
            viewModel.session.onAppForegrounded()
            advanceTimeBy(CampusSessionCoordinator.FOREGROUND_REFRESH_INITIAL_DELAY_MS)
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
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(CampusSessionCoordinator.FOREGROUND_REFRESH_ATTEMPTS, network.loginStatusFetchCount)

            network.loginStatus = LoginStatus(isLoggedIn = false)
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_changingSettingAffectsNextStartupOnly() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel(enabled = false)
            viewModel.preferences.setAutoLoginOnStartup(true)
            assertTrue(settings.autoLoginOnStartup.value)
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(0, network.loginCount)

            val restartedViewModel = createViewModel()
            restartedViewModel.session.onAppForegrounded()
            restartedViewModel.preferences.setAutoLoginOnStartup(false)
            advanceUntilIdle()
            assertFalse(settings.autoLoginOnStartup.value)
            assertEquals(0, network.loginCount)
        }

    @Test
    fun startupAutoLogin_manualLoginConsumesPendingAttempt() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            viewModel.session.onAppForegrounded()
            viewModel.session.login()
            viewModel.session.onAppForegrounded()
            advanceUntilIdle()
            assertEquals(1, network.loginCount)
        }

    @Test
    fun download_repeatedClicksAndChecksDoNotReplaceActiveTask() {
        updates.fetchResult = Result.success(availableUpdate())
        val viewModel = createViewModel()
        viewModel.update.setForeground(true)
        viewModel.update.check(true)
        viewModel.update.downloadAvailable()
        viewModel.update.downloadAvailable()
        viewModel.update.check(true)

        assertEquals(1, updates.downloadCount)
        assertEquals(1, updates.fetchCount)
        assertEquals(UpdateDownloadState.Downloading(), (viewModel.update.state.value as UpdateState.Available).download)
        viewModel.update.setForeground(false)
    }

    @Test
    fun download_completedInForegroundRequestsInstallationOnlyOnce() {
        updates.fetchResult = Result.success(availableUpdate())
        val viewModel = createViewModel()
        viewModel.update.setForeground(true)
        viewModel.update.check(true)
        viewModel.update.downloadAvailable()
        updates.publish(UpdateDownloadState.Ready)

        assertEquals(availableUpdate(), viewModel.update.installation.value)
        viewModel.update.consumeInstallation()
        updates.publish(UpdateDownloadState.Ready)
        assertNull(viewModel.update.installation.value)
        // 从安装器取消返回后，可手动安装同一个包，不再下载。
        viewModel.update.setForeground(false)
        viewModel.update.setForeground(true)
        assertNull(viewModel.update.installation.value)
        viewModel.update.downloadAvailable()
        assertEquals(availableUpdate(), viewModel.update.installation.value)
        assertEquals(1, updates.downloadCount)
        viewModel.update.setForeground(false)
    }

    @Test
    fun download_completedInBackgroundRestoresReadyWithoutLaunchingInstaller() {
        updates.fetchResult = Result.success(availableUpdate())
        val viewModel = createViewModel()
        viewModel.update.setForeground(true)
        viewModel.update.check(true)
        viewModel.update.downloadAvailable()
        viewModel.update.setForeground(false)
        updates.publish(UpdateDownloadState.Ready)
        viewModel.update.setForeground(true)

        assertEquals(UpdateState.Available(availableUpdate(), UpdateDownloadState.Ready), viewModel.update.state.value)
        assertNull(viewModel.update.installation.value)
        viewModel.update.setForeground(false)
    }

    @Test
    fun download_restoresAfterProcessRestartWithoutCheckingServer() {
        updates.transfer = UpdateTransfer(availableUpdate(), UpdateDownloadState.Downloading(42, true))
        val viewModel = createViewModel()
        viewModel.update.setForeground(true)
        assertEquals(UpdateState.Available(availableUpdate(), updates.transfer!!.state), viewModel.update.state.value)
        assertEquals(0, updates.fetchCount)
        assertEquals(0, updates.downloadCount)
        updates.publish(UpdateDownloadState.Ready)
        assertNull(viewModel.update.installation.value)
        viewModel.update.setForeground(false)
    }

    @Test
    fun download_dismissingDialogDisablesAutomaticInstallation() {
        updates.fetchResult = Result.success(availableUpdate())
        val viewModel = createViewModel()
        viewModel.update.setForeground(true)
        viewModel.update.check(true)
        viewModel.update.downloadAvailable()
        viewModel.update.cancelAutomaticInstall()
        updates.publish(UpdateDownloadState.Ready)
        assertNull(viewModel.update.installation.value)
        viewModel.update.setForeground(false)
    }

    @Test
    fun installation_permissionReturnRequiresSameVersionAndReadyFile() {
        updates.transfer = UpdateTransfer(availableUpdate(), UpdateDownloadState.Ready)
        val viewModel = createViewModel()
        viewModel.update.setForeground(true)
        viewModel.update.resumeInstallation(989)
        assertNull(viewModel.update.installation.value)
        viewModel.update.resumeInstallation(990)
        assertEquals(availableUpdate(), viewModel.update.installation.value)
        viewModel.update.consumeInstallation()
        updates.publish(UpdateDownloadState.Failed(UpdateDownloadFailure.MISSING_FILE))
        viewModel.update.resumeInstallation(990)
        assertNull(viewModel.update.installation.value)
        viewModel.update.setForeground(false)
    }

    @Test
    fun installation_revalidatesFileAndOffersRedownloadWhenMissing() = runTest {
        updates.transfer = UpdateTransfer(availableUpdate(), UpdateDownloadState.Ready)
        val viewModel = createViewModel()
        viewModel.update.setForeground(true)
        assertNull(viewModel.update.validatedDownload(availableUpdate()))
        assertEquals(
            UpdateDownloadState.Failed(UpdateDownloadFailure.INVALID_APK),
            (viewModel.update.state.value as UpdateState.Available).download
        )
        viewModel.update.downloadAvailable()
        assertEquals(1, updates.downloadCount)
        viewModel.update.setForeground(false)
    }

    @Test
    fun refreshStatus_prefersWlgnForDefaultWifiWithoutCreatingVerificationRecord() {
        network.info = CurrentNetworkInfo("bjut_wifi", "10.1.2.3", true, false, true)
        val viewModel = createViewModel()

        viewModel.session.refreshStatus()

        assertEquals(listOf(WifiLoginMethod.WLGN), network.loginStatusPreferredMethods)
        assertNull(settings.getWifiLoginMethod("bjut_wifi"))
    }

    @Test
    fun login_prefersRememberedMethodOverDefaultWlgn() = runTest {
        settings.setCredentials("2021001", "secret")
        settings.saveWifiLoginMethod("bjut_wifi", WifiLoginMethod.EPORTAL)
        network.info = CurrentNetworkInfo("bjut_wifi", "10.1.2.3", true, false, true)
        val viewModel = createViewModel()

        viewModel.session.login()

        assertEquals(WifiLoginMethod.EPORTAL, network.loginPreferredMethod)
    }

    @Test
    fun refreshStatus_remembersVerifiedMethodAndUsesItOnNextConnection() {
        network.info = CurrentNetworkInfo("bjut_wifi", "10.1.2.3", true, false, true)
        network.loginStatus = LoginStatus(false, verifiedMethod = WifiLoginMethod.WLGN)
        val viewModel = createViewModel()

        viewModel.session.refreshStatus()

        assertEquals(WifiLoginMethod.WLGN, settings.getWifiLoginMethod("bjut_wifi"))
        network.loginStatusPreferredMethods.clear()
        network.info = network.info.copy(ipAddress = "10.1.2.4")
        viewModel.session.refreshStatus()

        assertEquals(listOf(WifiLoginMethod.WLGN), network.loginStatusPreferredMethods)
    }

    @Test
    fun refreshStatus_updatesMethodAfterFallbackAndKeepsItOnVerificationFailure() {
        network.info = CurrentNetworkInfo("bjut_wifi", "10.1.2.3", true, false, true)
        settings.saveWifiLoginMethod("bjut_wifi", WifiLoginMethod.WLGN)
        network.loginStatus = LoginStatus(false, verifiedMethod = WifiLoginMethod.EPORTAL)
        val viewModel = createViewModel()
        viewModel.session.refreshStatus()
        assertEquals(WifiLoginMethod.EPORTAL, settings.getWifiLoginMethod("bjut_wifi"))

        network.loginStatus = LoginStatus(false, error = "unavailable")
        viewModel.session.refreshStatus()

        assertEquals(WifiLoginMethod.EPORTAL, settings.getWifiLoginMethod("bjut_wifi"))
    }

    @Test
    fun refreshStatus_doesNotReuseAnotherWifiMethod() {
        network.info = CurrentNetworkInfo("bjut_wifi", "10.1.2.3", true, false, true)
        settings.saveWifiLoginMethod("bjut_wifi", WifiLoginMethod.WLGN)
        val viewModel = createViewModel()
        network.loginStatusPreferredMethods.clear()
        network.info = network.info.copy(wifiName = "bjut-sushe-1")

        viewModel.session.refreshStatus()

        assertEquals(listOf<WifiLoginMethod?>(null), network.loginStatusPreferredMethods)
        assertNull(settings.getWifiLoginMethod("bjut-sushe-1"))
    }

    @Test
    fun refreshStatus_doesNotRememberMethodWhenNetworkChangesDuringVerification() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            network.info = CurrentNetworkInfo("bjut_wifi", "10.1.2.3", true, false, true)
            val viewModel = createViewModel()
            network.loginStatus = LoginStatus(false, verifiedMethod = WifiLoginMethod.WLGN)
            network.loginStatusGate = CompletableDeferred()
            viewModel.session.refreshStatus()
            network.info = network.info.copy(wifiName = "other_wifi")

            network.loginStatusGate!!.complete(Unit)
            runCurrent()

            assertNull(settings.getWifiLoginMethod("bjut_wifi"))
            assertNull(settings.getWifiLoginMethod("other_wifi"))
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

        viewModel.session.refreshStatus()

        val state = viewModel.session.uiState.value
        assertEquals("bjut_wifi", state.wifiName)
        assertEquals("10.1.2.3", state.ipAddress)
        assertTrue(state.isOnline)
        assertEquals(overview, state.accountOverview)
    }

    @Test
    fun refreshAccountInfo_keepsHomeContentUntilStatusAndOverviewArrive() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val original = viewModel.session.uiState.value
            val refreshedOverview = original.accountOverview!!.copy(usedTrafficMegabytes = "200")
            network.info = network.info.copy(wifiName = "bjut-sushe-1", ipAddress = "10.1.2.4")
            network.loginStatusGate = CompletableDeferred()
            selfService.overviewGate = CompletableDeferred()
            selfService.overviewResult = AccountOverviewResult.Success(refreshedOverview)

            viewModel.session.refreshAccountInfo()

            assertEquals(
                original.copy(isAccountInfoLoading = true, canLogoutDevices = false),
                viewModel.session.uiState.value
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
                viewModel.session.uiState.value
            )

            selfService.overviewGate!!.complete(Unit)
            runCurrent()

            assertEquals(refreshedOverview, viewModel.session.uiState.value.accountOverview)
            assertFalse(viewModel.session.uiState.value.isAccountInfoLoading)
            assertTrue(viewModel.session.uiState.value.isDeviceListAvailable)
            assertTrue(viewModel.session.uiState.value.canLogoutDevices)
        }

    @Test
    fun refreshAccountInfo_clearsCardsOnlyAfterLogoutIsConfirmed() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val original = viewModel.session.uiState.value
            network.loginStatus = LoginStatus(isLoggedIn = false)
            network.loginStatusGate = CompletableDeferred()

            viewModel.session.refreshAccountInfo()

            assertEquals(
                original.copy(isAccountInfoLoading = true, canLogoutDevices = false),
                viewModel.session.uiState.value
            )

            network.loginStatusGate!!.complete(Unit)
            runCurrent()

            val state = viewModel.session.uiState.value
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
        val original = viewModel.session.uiState.value
        network.loginStatus = LoginStatus(isLoggedIn = false, error = "timeout")

        viewModel.session.refreshAccountInfo()

        assertEquals(
            original.copy(accountInfoError = "timeout", canLogoutDevices = false),
            viewModel.session.uiState.value
        )
        assertEquals(1, selfService.overviewLoadCount)
        assertFalse(selfService.cleared)
    }

    @Test
    fun refreshAccountInfo_keepsExistingCardsWhenOverviewRequestFails() {
        val viewModel = createOnlineViewModel()
        val original = viewModel.session.uiState.value
        selfService.overviewResult = AccountOverviewResult.Failure("timeout")

        viewModel.session.refreshAccountInfo()

        assertEquals(
            original.copy(accountInfoError = "timeout", canLogoutDevices = false),
            viewModel.session.uiState.value
        )
    }

    @Test
    fun refreshAccountInfo_doesNotKeepPreviousAccountCardsAfterAccountChanges() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            network.loginStatus = LoginStatus(isLoggedIn = true, uid = "2021002")
            selfService.overviewGate = CompletableDeferred()
            val refreshedOverview = viewModel.session.uiState.value.accountOverview!!.copy(
                username = "2021002",
                devices = emptyList()
            )
            selfService.overviewResult = AccountOverviewResult.Success(refreshedOverview)

            viewModel.session.refreshAccountInfo()

            val loadingState = viewModel.session.uiState.value
            assertTrue(loadingState.isOnline)
            assertTrue(loadingState.isAccountInfoLoading)
            assertNull(loadingState.accountOverview)
            assertFalse(loadingState.isDeviceListAvailable)
            assertFalse(loadingState.canLogoutDevices)

            selfService.overviewGate!!.complete(Unit)
            runCurrent()

            assertEquals(refreshedOverview, viewModel.session.uiState.value.accountOverview)
            assertFalse(viewModel.session.uiState.value.isAccountInfoLoading)
        }

    @Test
    fun campusNetworkChange_retriesRetryableAccountFailure() =
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

            network.emitCampusNetworkChange(CampusNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(2, selfService.overviewLoadCount)
            assertEquals(overview, viewModel.session.uiState.value.accountOverview)
            assertEquals("", viewModel.session.uiState.value.accountInfoError)
        }

    @Test
    fun campusNetworkChange_doesNotRetryNonRetryableAccountFailure() =
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

            network.emitCampusNetworkChange(CampusNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(1, selfService.overviewLoadCount)
            assertEquals("invalid response", viewModel.session.uiState.value.accountInfoError)
        }

    @Test
    fun campusNetworkChange_showsRetryableFailureAfterAttemptsAreExhausted() =
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

            network.emitCampusNetworkChange(CampusNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(CampusSessionCoordinator.NETWORK_REFRESH_ATTEMPTS, selfService.overviewLoadCount)
            assertFalse(viewModel.session.uiState.value.isAccountInfoLoading)
            assertEquals("timeout", viewModel.session.uiState.value.accountInfoError)
        }

    @Test
    fun campusNetworkChange_doesNotRetryConfirmedLoggedOutStatus() =
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

            network.emitCampusNetworkChange(CampusNetworkChange.IP_ADDRESS_CHANGED)
            advanceUntilIdle()

            assertEquals(1, network.loginStatusFetchCount)
            assertFalse(viewModel.session.uiState.value.isOnline)
            assertEquals("", viewModel.session.uiState.value.networkStatusError)
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

        viewModel.session.refreshStatus()

        val state = viewModel.session.uiState.value
        assertFalse(state.isOnline)
        assertNull(state.accountOverview)
        assertTrue(selfService.cleared)
    }

    @Test
    fun login_returnsFailureWhenCredentialsMissing() = runTest {
        val viewModel = createViewModel()

        val result = viewModel.session.login()

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

        val result = viewModel.session.login()

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

        val result = viewModel.session.login()

        assertEquals(LoginResult.Success, result)
        assertEquals(WifiLoginMethod.WLGN, network.loginPreferredMethod)
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

            viewModel.session.onAppForegrounded()
            advanceTimeBy(CampusSessionCoordinator.FOREGROUND_REFRESH_INITIAL_DELAY_MS)
            runCurrent()
            assertEquals(1, network.loginStatusFetchCount)

            val loginStartedAt = testScheduler.currentTime
            assertEquals(LoginResult.Success, viewModel.session.login())

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
    fun foregroundInitialDelay_doesNotBlockDeviceLogout() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val device = viewModel.session.uiState.value.accountOverview!!.devices.single()
            selfService.logoutResult = DeviceLogoutResult.Failure("rejected")
            viewModel.session.onAppForegrounded()
            val initialDelayStartedAt = testScheduler.currentTime

            viewModel.session.logoutDevice(device)

            assertEquals(Triple(device.sessionId, device.ipAddress, device.macAddress), selfService.logoutRequest)
            assertEquals(initialDelayStartedAt, testScheduler.currentTime)
            advanceUntilIdle()
        }

    @Test
    fun deviceLogout_callerLeavingKeepsOneTaskAndRefreshesAfterSuccess() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val device = viewModel.session.uiState.value.accountOverview!!.devices.single()
            val gate = CompletableDeferred<Unit>()
            selfService.logoutGate = gate
            selfService.logoutResult = DeviceLogoutResult.Success
            val baseline = network.loginStatusFetchCount

            val caller = launch { viewModel.session.logoutDevice(device) }
            caller.cancelAndJoin()
            viewModel.session.logoutDevice(device)
            assertEquals(1, selfService.logoutCount)
            assertTrue(viewModel.session.uiState.value.isLoggingOutDevice)

            gate.complete(Unit)
            runCurrent()
            assertFalse(viewModel.session.uiState.value.isLoggingOutDevice)
            assertEquals("msg:${R.string.logout_device_success}", viewModel.session.uiState.value.deviceLogoutMessage)
            assertEquals(baseline, network.loginStatusFetchCount)
            advanceTimeBy(CampusSessionCoordinator.DEVICE_LOGOUT_REFRESH_DELAY_MS)
            runCurrent()
            assertEquals(baseline + 1, network.loginStatusFetchCount)
            viewModel.session.consumeDeviceLogoutMessage()
            assertNull(viewModel.session.uiState.value.deviceLogoutMessage)
        }

    @Test
    fun deviceLogout_indeterminateResultImmediatelyRechecksStatus() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val device = viewModel.session.uiState.value.accountOverview!!.devices.single()
            selfService.logoutResult = DeviceLogoutResult.Indeterminate("verify first")
            network.loginStatus = LoginStatus(isLoggedIn = false)
            val baseline = network.loginStatusFetchCount

            viewModel.session.logoutDevice(device)

            assertEquals(baseline + 1, network.loginStatusFetchCount)
            assertFalse(viewModel.session.uiState.value.isOnline)
            assertEquals("verify first", viewModel.session.uiState.value.deviceLogoutMessage)
            assertFalse(viewModel.session.uiState.value.isLoggingOutDevice)
        }

    @Test
    fun deviceLogout_failureKeepsSessionAndDoesNotRefresh() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val device = viewModel.session.uiState.value.accountOverview!!.devices.single()
            selfService.logoutResult = DeviceLogoutResult.Failure("rejected")
            val baseline = network.loginStatusFetchCount

            viewModel.session.logoutDevice(device)
            advanceUntilIdle()

            assertEquals(baseline, network.loginStatusFetchCount)
            assertTrue(viewModel.session.uiState.value.isOnline)
            assertEquals("rejected", viewModel.session.uiState.value.deviceLogoutMessage)
            assertFalse(viewModel.session.uiState.value.isLoggingOutDevice)
        }

    @Test
    fun deviceLogout_networkChangedWhileWaitingForSessionLockDoesNotSubmit() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val device = viewModel.session.uiState.value.accountOverview!!.devices.single()
            val gate = CompletableDeferred<Unit>()
            selfService.seamlessLoadGate = gate
            viewModel.session.refreshSeamlessInfo()
            viewModel.session.logoutDevice(device)
            assertEquals(0, selfService.logoutCount)
            assertTrue(viewModel.session.uiState.value.isLoggingOutDevice)

            network.info = network.info.copy(ipAddress = "10.1.2.99")
            gate.complete(Unit)
            runCurrent()

            assertEquals(0, selfService.logoutCount)
            assertFalse(viewModel.session.uiState.value.isLoggingOutDevice)
        }

    @Test
    fun deviceLogout_waitsForSharedSeamlessSessionLock() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val device = viewModel.session.uiState.value.accountOverview!!.devices.single()
            val gate = CompletableDeferred<Unit>()
            selfService.seamlessActionGate = gate
            viewModel.session.performSeamlessAction(SeamlessAction.Bind("00:11:22:33:44:66"))
            viewModel.session.logoutDevice(device)
            assertEquals(0, selfService.logoutCount)

            gate.complete(Unit)
            runCurrent()

            assertEquals(1, selfService.logoutCount)
            assertFalse(viewModel.session.uiState.value.isLoggingOutDevice)
        }

    @Test
    fun deviceLogout_applicationClearedCancelsPendingTask() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createOnlineViewModel()
            val store = ViewModelStore().apply { put("app", viewModel) }
            val device = viewModel.session.uiState.value.accountOverview!!.devices.single()
            val gate = CompletableDeferred<Unit>()
            selfService.logoutGate = gate
            selfService.logoutResult = DeviceLogoutResult.Success
            val baseline = network.loginStatusFetchCount
            viewModel.session.logoutDevice(device)
            assertTrue(viewModel.session.uiState.value.isLoggingOutDevice)

            store.clear()
            gate.complete(Unit)
            advanceUntilIdle()

            assertFalse(viewModel.session.uiState.value.isLoggingOutDevice)
            assertNull(viewModel.session.uiState.value.deviceLogoutMessage)
            assertEquals(baseline, network.loginStatusFetchCount)
        }

    @Test
    fun startupAutoLogin_disablingThenImmediatelyEnablingConsumesStartupOpportunity() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val viewModel = createStartupViewModel()
            viewModel.preferences.setAutoLoginOnStartup(false)
            viewModel.preferences.setAutoLoginOnStartup(true)

            viewModel.session.onAppForegrounded()
            advanceUntilIdle()

            assertEquals(0, network.loginCount)
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

            viewModel.session.refreshAfterDeviceLogout(successfulDeviceCount = 1)

            advanceTimeBy(CampusSessionCoordinator.DEVICE_LOGOUT_REFRESH_DELAY_MS - 1L)
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

            assertEquals(LoginResult.Success, viewModel.session.login())
            assertEquals(0, updates.fetchCount)

            advanceTimeBy(UpdateStateHolder.AUTOMATIC_UPDATE_CHECK_DELAY_MS)
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

            assertEquals(LoginResult.Success, viewModel.session.login())
            advanceUntilIdle()

            assertEquals(0, updates.fetchCount)
            assertEquals(0L, settings.lastUpdateCheckTimestamp)
        }

    @Test
    fun successfulLogin_respectsAutomaticUpdateCheckInterval() =
        runTest(mainDispatcherRule.testDispatcher.scheduler) {
            val now = 100_000_000L
            settings.lastUpdateCheckTimestamp =
                now - UpdateStateHolder.AUTOMATIC_UPDATE_CHECK_INTERVAL_MS + 1L
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

            assertEquals(LoginResult.Success, viewModel.session.login())
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

            assertEquals(LoginResult.Success, viewModel.session.login())
            advanceTimeBy(UpdateStateHolder.AUTOMATIC_UPDATE_CHECK_DELAY_MS - 1_000L)
            assertEquals(LoginResult.Success, viewModel.session.login())
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(0, updates.fetchCount)

            advanceTimeBy(UpdateStateHolder.AUTOMATIC_UPDATE_CHECK_DELAY_MS - 1_000L)
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

        viewModel.update.check(manual = true)

        assertEquals(UpdateState.Available(update), viewModel.update.state.value)
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
            viewModel.update.check(manual = true)
            assertEquals(UpdateState.UpToDate("99.0.0"), viewModel.update.state.value)
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
        assertEquals(CredentialSaveResult.SAVED, viewModel.accounts.selectAccount("bob"))
        viewModel.session.login()
        assertEquals("bob" to "b", network.performedLogin)
        viewModel.accounts.removeAccount("bob")
        viewModel.session.login()
        assertEquals("alice" to "a", network.performedLogin)
        viewModel.accounts.removeAccount("alice")
        assertEquals("", viewModel.accounts.selectedAccountUsername.value)
        assertTrue(viewModel.accounts.accounts.value.isEmpty())
    }

    @Test
    fun saveCredentials_forwardsResultAndDoesNotStoreOnFailure() = runTest {
        settings.saveResult = CredentialSaveResult.ENCRYPTION_UNAVAILABLE
        val viewModel = createViewModel()

        assertEquals(
            CredentialSaveResult.ENCRYPTION_UNAVAILABLE,
            viewModel.accounts.saveCredentials("2021001", "secret")
        )
        assertNull(settings.saved)

        settings.saveResult = CredentialSaveResult.SAVED
        assertEquals(
            CredentialSaveResult.SAVED,
            viewModel.accounts.saveCredentials("2021001", "secret")
        )
        assertEquals("2021001" to "secret", settings.saved)
    }

    @Test
    fun aboutVersionClick_fifthClickEnablesHttpLog() {
        val viewModel = createViewModel()

        repeat(HttpLogStateHolder.HTTP_LOG_UNLOCK_TAP_COUNT - 1) {
            assertFalse(viewModel.logs.onAboutVersionClicked())
        }
        assertFalse(viewModel.logs.httpLogEnabled.value)

        assertTrue(viewModel.logs.onAboutVersionClicked())
        assertTrue(viewModel.logs.httpLogEnabled.value)
        assertTrue(settings.httpLogEnabled.value)
        assertTrue(HttpLogStorage.isEnabled)
    }

    @Test
    fun disableHttpLog_disablesStorageAndClearsEntries() {
        val viewModel = createViewModel()
        repeat(HttpLogStateHolder.HTTP_LOG_UNLOCK_TAP_COUNT) {
            viewModel.logs.onAboutVersionClicked()
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

        viewModel.logs.disableHttpLog()

        assertFalse(viewModel.logs.httpLogEnabled.value)
        assertFalse(settings.httpLogEnabled.value)
        assertFalse(HttpLogStorage.isEnabled)
        assertTrue(HttpLogStorage.logs.value.isEmpty())
    }

    @Test
    fun onLocationPermissionChanged_updatesStateAndTriggersRefresh() {
        var granted = true
        val viewModel = createViewModel(hasLocationPermission = { granted })

        granted = false
        viewModel.session.onLocationPermissionChanged()

        assertFalse(viewModel.session.uiState.value.hasLocationPermission)
    }
}

private class FakeSettingsStore : AccountStore, WifiConfigStore, AppPreferences, HttpLogPreferences, UpdateCheckStore {
    private val wifiLoginMethods = mutableMapOf<String, WifiLoginMethod>()

    override fun getWifiLoginMethod(ssid: String): WifiLoginMethod? = wifiLoginMethods[ssid]

    override fun saveWifiLoginMethod(ssid: String, method: WifiLoginMethod) {
        wifiLoginMethods[ssid] = method
    }
    override suspend fun readActiveAccount(): top.steins.autologin.data.SavedAccount? =
        selectedAccountUsername.value.takeIf { it.isNotBlank() }?.let {
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
        if (this.selectedAccountUsername.value == username) {
            val next = accounts.value.firstOrNull()
            setCredentials(next?.username.orEmpty(), next?.password.orEmpty())
        }
        return CredentialSaveResult.SAVED
    }

    private val _targetSsids = MutableStateFlow(listOf("bjut_wifi"))
    override val targetSsids: StateFlow<List<String>> = _targetSsids.asStateFlow()

    private val _targetWifiConfigChanges = MutableSharedFlow<TargetWifiConfigChange>(
        extraBufferCapacity = 1
    )
    override val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange> =
        _targetWifiConfigChanges.asSharedFlow()

    private val _selectedAccountUsername = MutableStateFlow("")
    override val selectedAccountUsername: StateFlow<String> = _selectedAccountUsername.asStateFlow()

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
        _selectedAccountUsername.value = username
        _password.value = password
    }

    override fun addAutoDetectedTargetWifi(ssid: String): Boolean {
        if (!ssid.startsWith("bjut-sushe-")) return false
        if (ssid in _targetSsids.value) return false
        _targetSsids.value += ssid
        return true
    }

    override fun addTargetWifi(ssid: String) {
        if (ssid !in _targetSsids.value) _targetSsids.value += ssid
    }

    override fun removeTargetWifi(ssid: String) {
        _targetSsids.value = _targetSsids.value.filterNot { it == ssid }
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
    var seamlessLoadGate: CompletableDeferred<Unit>? = null
    var seamlessActionGate: CompletableDeferred<Unit>? = null
    var seamlessLoadResult: SeamlessLoadResult = SeamlessLoadResult.Success(SeamlessInfo("2021001", 1, SeamlessStatus.Enabled))
    var seamlessLoadCount = 0
    var seamlessActionCount = 0
    var seamlessRequestedAccount: String? = null
    var lastSeamlessAction: SeamlessAction? = null
    var onSeamlessAction: () -> Unit = {}

    override suspend fun loadSeamlessInfo(username: String): SeamlessLoadResult {
        seamlessLoadCount += 1
        seamlessRequestedAccount = username
        seamlessLoadGate?.await()
        return seamlessLoadResult
    }

    override suspend fun performSeamlessAction(info: SeamlessInfo, action: SeamlessAction): SeamlessActionResult {
        seamlessActionCount += 1
        lastSeamlessAction = action
        seamlessActionGate?.await()
        onSeamlessAction()
        return SeamlessActionResult.Success
    }
    var overviewGate: CompletableDeferred<Unit>? = null
    var overviewResult: AccountOverviewResult = AccountOverviewResult.Failure("fail")
    val overviewResults = ArrayDeque<AccountOverviewResult>()
    var overviewLoadCount = 0
    var lastRequestedUsername: String? = null
    var logoutGate: CompletableDeferred<Unit>? = null
    var logoutCount = 0
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
        logoutCount++
        logoutRequest = Triple(sessionId, ipAddress, macAddress)
        logoutGate?.await()
        return logoutResult
    }

    override suspend fun clearSession() {
        cleared = true
    }
}

private class FakeNetworkEnvironment : NetworkEnvironment {
    var loginGate: CompletableDeferred<Unit>? = null
    var loginStatusGate: CompletableDeferred<Unit>? = null
    private val campusNetworkChanges = MutableSharedFlow<CampusNetworkChange>(
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
    val loginStatusPreferredMethods = mutableListOf<WifiLoginMethod?>()
    var loginResult: LoginResult = LoginResult.Failure("")
    var performedLogin: Pair<String, String>? = null
    var loginPreferredMethod: WifiLoginMethod? = null
    var loginCount = 0
    var loginStatusAfterLogin: LoginStatus? = null
    var validatedInternet = false
    var loginStatusFetchCount = 0

    override fun observeCampusNetworkChanges(): Flow<CampusNetworkChange> = campusNetworkChanges

    fun emitCampusNetworkChange(change: CampusNetworkChange) {
        check(campusNetworkChanges.tryEmit(change))
    }

    override fun fetchCurrentNetworkInfo(canReadWifiName: Boolean): CurrentNetworkInfo = info

    override fun hasValidatedInternet(): Boolean = validatedInternet

    override suspend fun fetchLoginStatus(
        knownLoggedIn: Boolean,
        preferredMethod: WifiLoginMethod?
    ): LoginStatus {
        loginStatusFetchCount += 1
        loginStatusKnownOnlineRequests += knownLoggedIn
        loginStatusPreferredMethods += preferredMethod
        loginStatusGate?.await()
        return if (knownLoggedIn) knownOnlineLoginStatus else loginStatus
    }

    override suspend fun performLogin(
        username: String,
        password: String,
        preferredMethod: WifiLoginMethod?
    ): LoginResult {
        loginCount += 1
        performedLogin = username to password
        loginPreferredMethod = preferredMethod
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
