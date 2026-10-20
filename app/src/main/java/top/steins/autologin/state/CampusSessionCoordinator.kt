package top.steins.autologin.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steins.autologin.AccountInfoRefreshTrigger
import top.steins.autologin.AppStrings
import top.steins.autologin.HomeUiState
import top.steins.autologin.R
import top.steins.autologin.RefreshAttemptOutcome
import top.steins.autologin.data.AccountStore
import top.steins.autologin.data.AppPreferences
import top.steins.autologin.data.WifiConfigStore
import top.steins.autologin.data.WifiLoginMethod
import top.steins.autologin.network.AccountDevice
import top.steins.autologin.network.AccountOverviewResult
import top.steins.autologin.network.CampusNetworkChange
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.network.HttpLogStorage
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.NetworkEnvironment
import top.steins.autologin.network.SeamlessAction
import top.steins.autologin.network.SeamlessStatus
import top.steins.autologin.network.SelfServiceGateway

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
    private val selfService: SelfServiceGateway,
    private val network: NetworkEnvironment,
    private val hasLocationPermission: () -> Boolean,
    private val onLoginStarted: () -> Unit = {},
    private val onLoginSucceeded: () -> Unit = {}
) : AutoCloseable {

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
    private var refreshGeneration = 0L
    private val seamlessSession = object : SeamlessSessionAccess {
        override fun snapshot(): CampusSessionSnapshot {
            val state = _uiState.value
            return CampusSessionSnapshot(
                version = refreshGeneration,
                username = state.accountOverview?.username.orEmpty(),
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
    private val seamless = SeamlessCoordinator(
        scope = scope,
        strings = strings,
        selfService = selfService,
        network = network,
        session = seamlessSession,
        preferredWifiLoginMethod = ::preferredWifiLoginMethod,
        refreshNetworkStatus = { refreshStatus() }
    )
    val seamlessState = seamless.state

    fun refreshSeamlessInfo() = seamless.refresh()

    fun performSeamlessAction(action: SeamlessAction) = seamless.perform(action)

    private var refreshJob: Job? = null
    private var networkRefreshDebounceJob: Job? = null
    // 只读取启动时的开关；本次运行中开启设置不会立即触发登录。
    private var startupAutoLoginPending = autoLoginOnStartup.value

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
        startupAutoLoginPending = false
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
        networkRefreshDebounceJob?.cancel()
        networkRefreshDebounceJob = scope.launch {
            delay(NETWORK_REFRESH_DEBOUNCE_MS)
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
        refreshGeneration += 1
        seamless.invalidate(keepInfo = true)
        val generation = refreshGeneration
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                accountsReady.await()
                if (initialDelayMs > 0) delay(initialDelayMs)
                repeat(attempts) { attempt ->
                    HttpLogStorage.logAccountInfoRefresh(
                        trigger.description(strings, attempt + 1, attempts)
                    )
                    val outcome = accountOperationMutex.withLock {
                        refreshStatusInternal(
                            generation = generation,
                            clearSession = clearSession && attempt == 0,
                            showFinalError = attempt == attempts - 1,
                            knownLoggedIn = knownLoggedIn
                        )
                    }
                    if (outcome == RefreshAttemptOutcome.LoginSucceeded ||
                        outcome == RefreshAttemptOutcome.LoginAlreadyOnline
                    ) {
                        confirmLogin(knownLoggedIn = outcome == RefreshAttemptOutcome.LoginAlreadyOnline)
                        return@launch
                    }
                    if (outcome == RefreshAttemptOutcome.Complete) {
                        return@launch
                    }
                    // 启动状态检查耗尽后结束本次机会，避免稍后回到前台时才自动登录。
                    if (attempt == attempts - 1) startupAutoLoginPending = false
                    if (attempt < attempts - 1) delay(REFRESH_RETRY_DELAY_MS)
                }
            } finally {
                if (generation == refreshGeneration && !seamless.isLoading) {
                    seamless.finishRefresh()
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
        seamless.invalidate()
        startupAutoLoginPending = false
        onLoginStarted()
        _uiState.update { it.copy(isLoggingIn = true, loginMessage = null) }
        return scope.async {
            try {
                accountsReady.await()
                cancelPendingRefreshForLogin()
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

    private suspend fun loginOnCurrentNetwork(): LoginResult {
        val networkInfo = network.awaitCurrentNetworkInfo(
            canReadWifiName = _uiState.value.hasLocationPermission
        )
        if (networkInfo.isWifi) {
            wifiConfig.addAutoDetectedTargetWifi(networkInfo.wifiName)
        }
        // 后台保存/切换账号时，读取同一份快照，避免把不同账号的用户名和密码拼在一起。
        val account = accounts.readActiveAccount()
        val username = account?.username.orEmpty()
        val password = account?.password.orEmpty()
        return when {
            username.isBlank() || password.isBlank() ->
                LoginResult.Failure(strings.get(R.string.login_no_credentials))
            networkInfo.isCellular ->
                LoginResult.Failure(strings.get(R.string.login_cellular_blocked))
            !networkInfo.isWifi || networkInfo.wifiName !in wifiConfig.targetSsids.value ->
                LoginResult.Failure(strings.get(R.string.login_wrong_network))
            else -> network.performLogin(
                username,
                password,
                preferredMethod = preferredWifiLoginMethod(networkInfo.wifiName)
            )
        }
    }

    private fun preferredWifiLoginMethod(ssid: String): WifiLoginMethod? =
        wifiConfig.getWifiLoginMethod(ssid)
            ?: WifiLoginMethod.WLGN.takeIf { ssid == "bjut_wifi" }

    private suspend fun tryStartupAutoLogin(generation: Long): RefreshAttemptOutcome {
        if (!startupAutoLoginPending) return RefreshAttemptOutcome.Complete
        startupAutoLoginPending = false
        if (!autoLoginOnStartup.value || !_uiState.value.hasLocationPermission ||
            selectedAccountUsername.value.isBlank() || password.value.isBlank()
        ) {
            return RefreshAttemptOutcome.Complete
        }
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

    /** 用户主动登录优先于后台状态刷新，避免认证请求排在刷新重试之后。 */
    private suspend fun cancelPendingRefreshForLogin() {
        val pendingDebounceJob = networkRefreshDebounceJob
        pendingDebounceJob?.cancelAndJoin()
        if (networkRefreshDebounceJob === pendingDebounceJob) {
            networkRefreshDebounceJob = null
        }

        val pendingRefreshJob = refreshJob
        pendingRefreshJob?.cancelAndJoin()
        if (refreshJob === pendingRefreshJob) {
            refreshJob = null
        }

        // 使取消前已分配的刷新代次失效，避免旧状态覆盖后续登录确认结果。
        refreshGeneration += 1
    }

    /** 操作归应用生命周期持有，页面离开或重建不会取消已提交的下线请求。 */
    fun logoutDevice(device: AccountDevice) {
        val snapshot = _uiState.value
        if (!snapshot.isOnline || !snapshot.canLogoutDevices || snapshot.isLoggingOutDevice ||
            snapshot.isLoggingIn || snapshot.accountOverview?.devices?.contains(device) != true
        ) return
        _uiState.update { it.copy(isLoggingOutDevice = true, deviceLogoutMessage = null) }
        scope.launch {
            try {
                val result = accountOperationMutex.withLock {
                    val current = _uiState.value
                    val networkInfo = network.fetchCurrentNetworkInfo(current.hasLocationPermission)
                    if (!current.isOnline || current.accountOverview?.username != snapshot.accountOverview.username ||
                        current.wifiName != snapshot.wifiName || current.ipAddress != snapshot.ipAddress ||
                        !networkInfo.isWifi || networkInfo.wifiName != snapshot.wifiName ||
                        networkInfo.ipAddress != snapshot.ipAddress
                    ) return@launch
                    selfService.logoutDevice(device.sessionId, device.ipAddress, device.macAddress)
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
        if (clearSession) selfService.clearSession()

        val networkInfo = network.fetchCurrentNetworkInfo(
            canReadWifiName = _uiState.value.hasLocationPermission
        )
        if (generation == refreshGeneration &&
            (networkInfo.wifiName != _uiState.value.wifiName || networkInfo.ipAddress != _uiState.value.ipAddress)
        ) {
            seamless.invalidate()
        }
        if (networkInfo.isWifi) {
            wifiConfig.addAutoDetectedTargetWifi(networkInfo.wifiName)
        }
        val isTargetWifi = networkInfo.isWifi &&
                networkInfo.wifiName in wifiConfig.targetSsids.value

        if (!isTargetWifi) {
            // 初次权限弹窗期间可能读不到 SSID，保留机会直到权限结果返回。
            if (_uiState.value.hasLocationPermission || !networkInfo.isWifi) {
                startupAutoLoginPending = false
            }
            selfService.clearSession()
            if (generation == refreshGeneration) seamless.invalidate()
            updateState(generation) {
                copy(
                    wifiName = networkInfo.wifiName,
                    ipAddress = networkInfo.ipAddress,
                    isOnline = false,
                    accountOverview = null,
                    seamlessStatus = SeamlessStatus.Unknown,
                    isAccountInfoLoading = false,
                    accountInfoError = "",
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
                    networkStatusError = ""
                )
            }
            return RefreshAttemptOutcome.Complete
        }

        // 查询期间保留上次确认的网络和卡片内容，避免把“等待结果”呈现为离线。
        updateState(generation) {
            copy(isAccountInfoLoading = true, canLogoutDevices = false)
        }

        val status = network.fetchLoginStatus(
            knownLoggedIn = knownLoggedIn,
            preferredMethod = preferredWifiLoginMethod(networkInfo.wifiName)
        )
        if (status.error.isBlank() && status.verifiedMethod != null) {
            val current = network.fetchCurrentNetworkInfo(
                canReadWifiName = _uiState.value.hasLocationPermission
            )
            // 网络切换后返回的旧结果不能覆盖该 Wi-Fi 的验证方式。
            if (current.isWifi && current.wifiName == networkInfo.wifiName &&
                current.ipAddress == networkInfo.ipAddress
            ) {
                wifiConfig.saveWifiLoginMethod(networkInfo.wifiName, status.verifiedMethod)
            }
        }
        if (!status.isLoggedIn) {
            if (status.error.isNotBlank()) {
                updateState(generation) {
                    copy(
                        wifiName = if (isOnline) wifiName else networkInfo.wifiName,
                        ipAddress = if (isOnline) ipAddress else networkInfo.ipAddress,
                        isAccountInfoLoading = !showFinalError,
                        accountInfoError = if (isOnline && showFinalError) {
                            status.error
                        } else {
                            accountInfoError
                        },
                        networkStatusError = if (!isOnline && showFinalError) {
                            status.error
                        } else {
                            networkStatusError
                        },
                        canLogoutDevices = false
                    )
                }
                return RefreshAttemptOutcome.RetryableFailure
            }
            selfService.clearSession()
            if (generation == refreshGeneration) seamless.invalidate()
            updateState(generation) {
                copy(
                    wifiName = networkInfo.wifiName,
                    ipAddress = networkInfo.ipAddress,
                    isOnline = false,
                    accountOverview = null,
                    seamlessStatus = SeamlessStatus.Unknown,
                    isAccountInfoLoading = false,
                    accountInfoError = "",
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
                    networkStatusError = ""
                )
            }
            // 已成功访问校园网状态页，只是尚未认证；继续检查只会阻塞用户登录。
            return tryStartupAutoLogin(generation)
        }

        startupAutoLoginPending = false

        // 自助服务账号必须与当前校园网认证账号一致，直接采用 lgn 注销页返回的 uid。
        val username = status.uid
        if (username.isBlank()) {
            selfService.clearSession()
            if (generation == refreshGeneration) seamless.invalidate()
            updateState(generation) {
                copy(
                    wifiName = networkInfo.wifiName,
                    ipAddress = networkInfo.ipAddress,
                    isOnline = true,
                    accountOverview = null,
                    seamlessStatus = SeamlessStatus.Unknown,
                    isAccountInfoLoading = false,
                    accountInfoError = strings.get(R.string.account_info_fetch_failed),
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
                    networkStatusError = ""
                )
            }
            return RefreshAttemptOutcome.Complete
        }

        if (generation == refreshGeneration && _uiState.value.accountOverview?.username != username) {
            seamless.invalidate()
        }
        updateState(generation) {
            // 同一认证账号刷新时保留旧数据；切换账号后不得沿用上一账号的内容。
            val keepOverview = isOnline && accountOverview?.username == username
            copy(
                wifiName = networkInfo.wifiName,
                ipAddress = networkInfo.ipAddress,
                isOnline = true,
                accountOverview = if (keepOverview) accountOverview else null,
                seamlessStatus = if (keepOverview) seamlessStatus else SeamlessStatus.Unknown,
                isAccountInfoLoading = true,
                accountInfoError = if (keepOverview) accountInfoError else "",
                isDeviceListAvailable = keepOverview && isDeviceListAvailable,
                canLogoutDevices = false,
                networkStatusError = ""
            )
        }

        return when (val result = selfService.loadAccountOverview(username)) {
            is AccountOverviewResult.Success -> {
                updateState(generation) {
                    copy(
                        isOnline = true,
                        accountOverview = result.overview,
                        seamlessStatus = if (accountOverview?.username == result.overview.username) seamlessStatus else SeamlessStatus.Unknown,
                        isAccountInfoLoading = false,
                        accountInfoError = result.warningMessage,
                        isDeviceListAvailable = result.isDeviceListAvailable,
                        canLogoutDevices = result.canLogoutDevices
                    )
                }
                if (generation == refreshGeneration) refreshSeamlessInfo()
                RefreshAttemptOutcome.Complete
            }

            is AccountOverviewResult.Failure -> {
                updateState(generation) {
                    copy(
                        isOnline = true,
                        isAccountInfoLoading = result.isRetryable && !showFinalError,
                        accountInfoError = if (!result.isRetryable || showFinalError) {
                            result.message
                        } else {
                            accountInfoError
                        },
                        canLogoutDevices = false
                    )
                }
                if (result.isRetryable) {
                    RefreshAttemptOutcome.RetryableFailure
                } else {
                    RefreshAttemptOutcome.Complete
                }
            }
        }
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

    override fun close() {
        selfService.close()
        network.close()
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
