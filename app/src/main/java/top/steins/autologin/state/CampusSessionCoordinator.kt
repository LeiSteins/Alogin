package top.steins.autologin.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steins.autologin.AccountInfoRefreshTrigger
import top.steins.autologin.AppStrings
import top.steins.autologin.HomeUiState
import top.steins.autologin.R
import top.steins.autologin.RefreshAttemptOutcome
import top.steins.autologin.SeamlessUiState
import top.steins.autologin.domain.model.AccountDevice
import top.steins.autologin.domain.model.AccountOverviewResult
import top.steins.autologin.domain.model.CampusNetworkChange
import top.steins.autologin.domain.model.DeviceLogoutResult
import top.steins.autologin.domain.model.LoginResult
import top.steins.autologin.domain.model.SeamlessAction
import top.steins.autologin.domain.model.SeamlessStatus
import top.steins.autologin.domain.port.AccountSessionGateway
import top.steins.autologin.domain.port.AccountStore
import top.steins.autologin.domain.port.AppPreferences
import top.steins.autologin.domain.port.CampusAuthentication
import top.steins.autologin.domain.port.NetworkMonitor
import top.steins.autologin.domain.port.SeamlessGateway
import top.steins.autologin.domain.port.WifiConfigStore

/**
 * 协调网络变化、认证状态和自助服务会话，避免 Compose 生命周期与并发网络请求互相覆盖状态。
 *
 * 登录、刷新、设备操作与无感知共用一把会话锁；更新通过回调在组装层连接。
 */
class CampusSessionCoordinator(
    private val scope: CoroutineScope,
    private val strings: AppStrings,
    private val accounts: AccountStore,
    private val wifiConfig: WifiConfigStore,
    preferences: AppPreferences,
    selfService: AccountSessionGateway,
    seamlessService: SeamlessGateway,
    private val network: NetworkMonitor,
    authentication: CampusAuthentication,
    private val logAccountInfoRefresh: (String) -> Unit,
    private val hasLocationPermission: () -> Boolean,
    private val onLoginStarted: () -> Unit = {},
    private val onLoginSucceeded: () -> Unit = {}
) {

    val targetSsids = wifiConfig.targetSsids
    private val selectedAccountUsername = accounts.selectedAccountUsername
    private val password = accounts.password
    private val autoLoginOnStartup = preferences.autoLoginOnStartup

    private val accountOperationMutex = Mutex()
    private val accountsReady = scope.async { accounts.initialize() }
    private var loginJob: Deferred<LoginResult>? = null

    private val _uiState = MutableStateFlow(
        HomeUiState(
            wifiName = strings.get(R.string.loading),
            ipAddress = strings.get(R.string.loading),
            hasLocationPermission = hasLocationPermission()
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val refreshRunner = SessionRefreshRunner(scope)
    private val refreshGeneration: Long get() = refreshRunner.generation
    private val loginExecutor = CampusLoginExecutor(strings, accounts, wifiConfig, network, authentication)
    // 门户确认的身份独立于主页账号卡片；概览失败不应阻断其他已认证功能。
    private var authenticatedUsername = ""
    private val seamlessSession = object : SeamlessSessionAccess {
        override fun snapshot(): CampusSessionSnapshot {
            val state = _uiState.value
            return CampusSessionSnapshot(
                version = refreshGeneration,
                username = authenticatedUsername,
                wifiName = state.wifiName,
                ipAddress = state.ipAddress,
                isOnline = state.isOnline,
                isRefreshing = state.isAccountInfoLoading
            )
        }

        override fun isCurrent(expected: CampusSessionSnapshot): Boolean = expected == snapshot()

        override suspend fun withSession(
            expected: CampusSessionSnapshot,
            operation: suspend () -> Unit
        ) {
            accountOperationMutex.withLock {
                if (isCurrent(expected)) operation()
            }
        }

        override fun reportStatus(expected: CampusSessionSnapshot, status: SeamlessStatus) {
            if (isCurrent(expected) && expected.isOnline && expected.username.isNotBlank()) {
                _uiState.update { it.copy(seamlessStatus = status) }
            }
        }
    }
    private val seamlessCoordinator = SeamlessCoordinator(
        scope = scope,
        strings = strings,
        selfService = seamlessService,
        authentication = authentication,
        session = seamlessSession,
        preferredWifiLoginMethod = loginExecutor::preferredMethod,
        refreshNetworkStatus = { refreshStatus() }
    )
    val seamless: SeamlessFeature = object : SeamlessFeature {
        override val state: StateFlow<SeamlessUiState> = seamlessCoordinator.state
        override val localIpAddress: StateFlow<String> = uiState.map { it.ipAddress }
            .stateIn(scope, SharingStarted.Eagerly, uiState.value.ipAddress)

        override fun refresh() = seamlessCoordinator.refresh()
        override fun refreshSession() = refreshAccountInfo()
        override fun perform(action: SeamlessAction) = seamlessCoordinator.perform(action)
    }
    private val refreshLoader = SessionRefreshLoader(
        network, authentication, wifiConfig, selfService, loginExecutor::preferredMethod
    )
    private val deviceLogoutExecutor = DeviceLogoutExecutor(network, selfService)

    // 只读取启动时的开关；本次运行中开启设置不会立即触发登录。
    private val startupAutoLogin = StartupAutoLoginPolicy(autoLoginOnStartup.value)

    init {
        network.observeCampusNetworkChanges()
            .conflate()
            .collectInSession { change -> scheduleNetworkRefresh(change) }

        wifiConfig.targetWifiConfigChanges.collectInSession { change ->
            startRefresh(
                trigger = AccountInfoRefreshTrigger.TargetWifiConfigurationChanged(change),
                clearSession = false,
                attempts = 1,
                initialDelayMs = 0
            )
        }
    }

    fun cancelStartupAutoLogin() {
        startupAutoLogin.cancel()
    }

    fun onLocationPermissionChanged() {
        val granted = hasLocationPermission()
        _uiState.update { it.copy(hasLocationPermission = granted) }
        startRefresh(
            trigger = AccountInfoRefreshTrigger.LocationPermissionResult(granted),
            clearSession = false,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    /**
     * 部分设备刚回到前台时会短暂返回脱敏 SSID；延迟并重试，避免误判为非目标 WiFi。
     */
    fun onAppForegrounded() {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.AppForegrounded,
            clearSession = false,
            attempts = FOREGROUND_REFRESH_ATTEMPTS,
            initialDelayMs = FOREGROUND_REFRESH_INITIAL_DELAY_MS
        )
    }

    fun refreshStatus(clearSession: Boolean = false) {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.ManualNetworkStatusCheck,
            clearSession = clearSession,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    fun refreshAccountInfo() {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.ManualAccountInfoRefresh,
            clearSession = false,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    fun retryAccountInfo() {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.AccountInfoRetry,
            clearSession = false,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    fun refreshAfterDeviceLogout(successfulDeviceCount: Int) {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.DeviceLogoutSucceeded(successfulDeviceCount),
            clearSession = false,
            attempts = 1,
            initialDelayMs = DEVICE_LOGOUT_REFRESH_DELAY_MS
        )
    }

    fun refreshAfterIndeterminateDeviceLogout() {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.DeviceLogoutIndeterminate,
            clearSession = false,
            attempts = 1,
            initialDelayMs = 0
        )
    }

    fun confirmLogin(knownLoggedIn: Boolean = false) {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.LoginConfirmation,
            clearSession = false,
            attempts = LOGIN_CONFIRMATION_ATTEMPTS,
            initialDelayMs = LOGIN_CONFIRMATION_INITIAL_DELAY_MS,
            knownLoggedIn = knownLoggedIn
        )
    }

    private fun scheduleNetworkRefresh(change: CampusNetworkChange) {
        refreshRunner.debounce(NETWORK_REFRESH_DEBOUNCE_MS) {
            startRefresh(
                trigger = AccountInfoRefreshTrigger.CampusNetworkChanged(change),
                clearSession = false,
                attempts = NETWORK_REFRESH_ATTEMPTS,
                initialDelayMs = 0
            )
        }
    }

    private fun startRefresh(
        trigger: AccountInfoRefreshTrigger,
        clearSession: Boolean,
        attempts: Int,
        initialDelayMs: Long,
        knownLoggedIn: Boolean = false
    ) {
        refreshRunner.start(
            attempts = attempts,
            initialDelayMs = initialDelayMs,
            retryDelayMs = REFRESH_RETRY_DELAY_MS,
            prepare = { accountsReady.await() },
            onStart = { seamlessCoordinator.invalidate(keepInfo = true) },
            onFinish = { generation ->
                if (generation == refreshGeneration && !seamlessCoordinator.isLoading) seamlessCoordinator.finishRefresh()
            }
        ) { generation, attempt ->
            logAccountInfoRefresh(trigger.description(strings, attempt + 1, attempts))
            val outcome = accountOperationMutex.withLock {
                refreshStatusInternal(
                    generation = generation,
                    clearSession = clearSession && attempt == 0,
                    showFinalError = attempt == attempts - 1,
                    knownLoggedIn = knownLoggedIn
                )
            }
            when (outcome) {
                RefreshAttemptOutcome.LoginSucceeded, RefreshAttemptOutcome.LoginAlreadyOnline -> {
                    confirmLogin(knownLoggedIn = outcome == RefreshAttemptOutcome.LoginAlreadyOnline)
                    true
                }
                RefreshAttemptOutcome.Complete -> true
                RefreshAttemptOutcome.RetryableFailure -> {
                    if (attempt == attempts - 1) startupAutoLogin.cancel()
                    false
                }
            }
        }
    }

    fun requestLogin() {
        beginLogin()
    }

    /** 等待结果的调用方取消时，认证任务仍由 ViewModel 持有。 */
    suspend fun login(): LoginResult = beginLogin().await()

    fun consumeLoginMessage() {
        _uiState.update { it.copy(loginMessage = null) }
    }

    private fun beginLogin(): Deferred<LoginResult> {
        loginJob?.takeIf { it.isActive }?.let { return it }
        seamlessCoordinator.invalidate()
        startupAutoLogin.cancel()
        onLoginStarted()
        _uiState.update { it.copy(isLoggingIn = true, loginMessage = null) }
        return scope.async {
            try {
                accountsReady.await()
                refreshRunner.cancelForLogin()
                _uiState.update { it.copy(isAccountInfoLoading = false) }
                val result = accountOperationMutex.withLock { loginOnCurrentNetwork() }
                val message = when (result) {
                    LoginResult.Success -> {
                        confirmLogin()
                        onLoginSucceeded()
                        strings.get(R.string.home_login_success_fetching)
                    }
                    LoginResult.AlreadyOnline -> {
                        confirmLogin(knownLoggedIn = true)
                        onLoginSucceeded()
                        strings.get(R.string.home_already_online_fetching)
                    }
                    is LoginResult.Failure -> result.message
                    is LoginResult.NetworkError -> result.message
                }
                _uiState.update { it.copy(loginMessage = message) }
                result
            } finally {
                _uiState.update { it.copy(isLoggingIn = false) }
            }
        }.also { loginJob = it }
    }

    private suspend fun loginOnCurrentNetwork(): LoginResult =
        loginExecutor.login(canReadWifiName = _uiState.value.hasLocationPermission)

    private suspend fun tryStartupAutoLogin(generation: Long): RefreshAttemptOutcome {
        if (!startupAutoLogin.consume(
                enabled = autoLoginOnStartup.value,
                hasPermission = _uiState.value.hasLocationPermission,
                hasCredentials = selectedAccountUsername.value.isNotBlank() && password.value.isNotBlank()
            )
        ) return RefreshAttemptOutcome.Complete
        updateState(generation) { copy(isAccountInfoLoading = true) }
        onLoginStarted()
        return when (val result = loginOnCurrentNetwork()) {
            LoginResult.Success -> {
                onLoginSucceeded()
                RefreshAttemptOutcome.LoginSucceeded
            }
            LoginResult.AlreadyOnline -> {
                onLoginSucceeded()
                RefreshAttemptOutcome.LoginAlreadyOnline
            }
            is LoginResult.Failure -> {
                updateState(generation) {
                    copy(isAccountInfoLoading = false, networkStatusError = result.message)
                }
                RefreshAttemptOutcome.Complete
            }
            is LoginResult.NetworkError -> {
                updateState(generation) {
                    copy(isAccountInfoLoading = false, networkStatusError = result.message)
                }
                RefreshAttemptOutcome.Complete
            }
        }
    }

    /** 操作归应用生命周期持有，页面离开或重建不会取消已提交的下线请求。 */
    fun logoutDevice(device: AccountDevice) {
        val request = deviceLogoutExecutor.prepare(device, _uiState.value) ?: return
        _uiState.update { it.copy(isLoggingOutDevice = true, deviceLogoutMessage = null) }
        scope.launch {
            try {
                val outcome = accountOperationMutex.withLock {
                    deviceLogoutExecutor.execute(request, _uiState.value)
                }
                val result = when (outcome) {
                    DeviceLogoutOutcome.Skipped -> return@launch
                    is DeviceLogoutOutcome.Completed -> outcome.result
                }
                val message = when (result) {
                    DeviceLogoutResult.Success -> {
                        refreshAfterDeviceLogout(1)
                        strings.get(R.string.logout_device_success, device.macAddress)
                    }
                    is DeviceLogoutResult.Failure -> result.message
                    is DeviceLogoutResult.Indeterminate -> {
                        refreshAfterIndeterminateDeviceLogout()
                        result.message
                    }
                }
                _uiState.update { it.copy(deviceLogoutMessage = message) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(deviceLogoutMessage = strings.get(R.string.logout_failed_generic))
                }
            } finally {
                _uiState.update { it.copy(isLoggingOutDevice = false) }
            }
        }
    }

    fun consumeDeviceLogoutMessage() {
        _uiState.update { it.copy(deviceLogoutMessage = null) }
    }

    private suspend fun refreshStatusInternal(
        generation: Long,
        clearSession: Boolean,
        showFinalError: Boolean,
        knownLoggedIn: Boolean
    ): RefreshAttemptOutcome {
        val result = refreshLoader.load(
            canReadWifiName = _uiState.value.hasLocationPermission,
            clearSession = clearSession,
            knownLoggedIn = knownLoggedIn,
            onProgress = { applyRefreshProgress(generation, it) }
        )
        return when (result) {
            is SessionRefreshResult.OutsideTargetNetwork -> {
                updateState(generation) { withoutAccount(result.info) }
                RefreshAttemptOutcome.Complete
            }
            is SessionRefreshResult.Offline -> {
                updateState(generation) { withoutAccount(result.info) }
                // 已成功访问状态页但尚未认证，交给启动策略决定是否登录。
                tryStartupAutoLogin(generation)
            }
            is SessionRefreshResult.IdentityUnavailable -> {
                updateState(generation) {
                    withoutAccount(result.info, online = true, error = strings.get(R.string.account_info_fetch_failed))
                }
                RefreshAttemptOutcome.Complete
            }
            is SessionRefreshResult.AuthenticationUnavailable -> {
                updateState(generation) { authenticationUnavailable(result.info, result.error, showFinalError) }
                RefreshAttemptOutcome.RetryableFailure
            }
            is SessionRefreshResult.AccountLoaded -> {
                updateState(generation) { accountOverviewLoaded(result.result, showFinalError) }
                val retryable = (result.result as? AccountOverviewResult.Failure)?.isRetryable == true
                if (generation == refreshGeneration && (!retryable || showFinalError)) seamlessCoordinator.refresh()
                if (retryable) RefreshAttemptOutcome.RetryableFailure else RefreshAttemptOutcome.Complete
            }
        }
    }

    private fun applyRefreshProgress(generation: Long, progress: SessionRefreshProgress) {
        when (progress) {
            is SessionRefreshProgress.NetworkRead -> {
                val info = progress.info
                if (generation == refreshGeneration &&
                    (info.wifiName != _uiState.value.wifiName || info.ipAddress != _uiState.value.ipAddress)
                ) clearAuthenticatedIdentity()
            }
            SessionRefreshProgress.CheckingAuthentication -> updateState(generation) {
                // 查询期间保留上次确认的网络和卡片内容，避免把等待结果呈现为离线。
                copy(isAccountInfoLoading = true, canLogoutDevices = false)
            }
            is SessionRefreshProgress.OutsideTargetNetwork -> {
                // 初次权限弹窗期间可能读不到 SSID，保留机会直到权限结果返回。
                if (_uiState.value.hasLocationPermission || !progress.info.isWifi) startupAutoLogin.cancel()
                if (generation == refreshGeneration) clearAuthenticatedIdentity()
            }
            SessionRefreshProgress.IdentityCleared -> {
                if (generation == refreshGeneration) clearAuthenticatedIdentity()
            }
            is SessionRefreshProgress.Authenticated -> {
                startupAutoLogin.cancel()
                if (generation == refreshGeneration) {
                    if (progress.username.isBlank()) clearAuthenticatedIdentity()
                    else {
                        if (authenticatedUsername != progress.username) seamlessCoordinator.invalidate()
                        authenticatedUsername = progress.username
                    }
                }
                if (progress.username.isNotBlank()) {
                    updateState(generation) { authenticated(progress.info, progress.username) }
                }
            }
        }
    }

    private fun clearAuthenticatedIdentity() {
        authenticatedUsername = ""
        seamlessCoordinator.invalidate()
    }

    private fun updateState(generation: Long, transform: HomeUiState.() -> HomeUiState) {
        if (generation == refreshGeneration) {
            _uiState.update { it.transform() }
        }
    }

    private fun <T> Flow<T>.collectInSession(onValue: (T) -> Unit) {
        scope.launch {
            collect { onValue(it) }
        }
    }

    companion object {
        const val NETWORK_REFRESH_DEBOUNCE_MS = 250L
        const val NETWORK_REFRESH_ATTEMPTS = 2
        const val FOREGROUND_REFRESH_INITIAL_DELAY_MS = 500L
        const val FOREGROUND_REFRESH_ATTEMPTS = 2
        const val LOGIN_CONFIRMATION_ATTEMPTS = 3
        const val LOGIN_CONFIRMATION_INITIAL_DELAY_MS = 500L
        const val DEVICE_LOGOUT_REFRESH_DELAY_MS = 2_000L
        const val REFRESH_RETRY_DELAY_MS = 500L
    }
}
