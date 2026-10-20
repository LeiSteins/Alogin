package top.steins.autologin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steins.autologin.data.AppearanceMode
import top.steins.autologin.data.CredentialSaveResult
import top.steins.autologin.data.SettingsGateway
import top.steins.autologin.data.WifiLoginMethod
import top.steins.autologin.network.AccountOverviewResult
import top.steins.autologin.network.DefaultNetworkChange
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.network.HttpLogEntry
import top.steins.autologin.network.HttpLogStorage
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.NetworkEnvironment
import top.steins.autologin.network.SelfServiceGateway
import top.steins.autologin.network.SeamlessAction
import top.steins.autologin.network.SeamlessActionResult
import top.steins.autologin.network.SeamlessLoadResult
import top.steins.autologin.network.SeamlessStatus
import top.steins.autologin.network.update.UpdateGateway
import top.steins.autologin.network.update.UpdateState

/**
 * 协调网络变化、认证状态和自助服务会话，避免 Compose 生命周期与并发网络请求互相覆盖状态。
 *
 * 所有依赖均通过构造函数注入，界面不直接接触存储与网络实现。
 */
class AppViewModel(
    private val strings: AppStrings,
    private val settings: SettingsGateway,
    private val selfService: SelfServiceGateway,
    private val updates: UpdateGateway,
    private val network: NetworkEnvironment,
    private val hasLocationPermission: () -> Boolean,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) : ViewModel() {

    val accounts = settings.accounts
    val username: StateFlow<String> = settings.username
    val password: StateFlow<String> = settings.password
    val targetWifis: StateFlow<List<String>> = settings.targetWifis
    val appearanceMode: StateFlow<AppearanceMode> = settings.appearanceMode
    val autoLoginOnStartup: StateFlow<Boolean> = settings.autoLoginOnStartup
    val httpLogEnabled: StateFlow<Boolean> = settings.httpLogEnabled
    val credentialResetPending: StateFlow<Boolean> = settings.credentialResetPending
    val httpLogs: StateFlow<List<HttpLogEntry>> = HttpLogStorage.logs

    private val accountOperationMutex = Mutex()
    private val settingsReady = viewModelScope.async { settings.initialize() }
    private var loginJob: Deferred<LoginResult>? = null

    private val _uiState = MutableStateFlow(
        AppUiState(
            wifiName = strings.get(R.string.loading),
            ipAddress = strings.get(R.string.loading),
            hasLocationPermission = hasLocationPermission()
        )
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()
    private val _seamlessState = MutableStateFlow(SeamlessUiState())
    val seamlessState: StateFlow<SeamlessUiState> = _seamlessState.asStateFlow()
    private var seamlessEpoch = 0L
    private var seamlessLoadJob: Job? = null
    private var seamlessMutationJob: Job? = null

    private fun invalidateSeamless(keepInfo: Boolean = false) {
        seamlessEpoch += 1
        seamlessLoadJob?.cancel()
        _seamlessState.value = if (keepInfo) {
            _seamlessState.value.copy(isLoading = true)
        } else {
            SeamlessUiState(isSubmitting = seamlessMutationJob?.isActive == true)
        }
    }

    fun refreshSeamlessInfo() {
        if (seamlessMutationJob?.isActive == true || seamlessLoadJob?.isActive == true) return
        if (_uiState.value.isAccountInfoLoading) return
        val account = _uiState.value.accountOverview?.username
        if (!_uiState.value.isOnline || account.isNullOrBlank()) {
            _seamlessState.value = SeamlessUiState(error = strings.get(R.string.seamless_requires_login))
            return
        }
        val epoch = seamlessEpoch
        _seamlessState.update { it.copy(isLoading = true) }
        seamlessLoadJob = viewModelScope.launch {
            try {
                accountOperationMutex.withLock {
                    if (epoch == seamlessEpoch) loadSeamlessLocked(account, epoch)
                }
            } finally {
                if (epoch == seamlessEpoch) _seamlessState.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadSeamlessLocked(account: String, epoch: Long) {
        val result = selfService.loadSeamlessInfo(account)
        if (epoch != seamlessEpoch) return
        if (result is SeamlessLoadResult.Success && result.info.status != SeamlessStatus.Unknown &&
            result.info.username.substringBefore("@").trim() == account.substringBefore("@").trim()
        ) {
            // 主页只保存服务端已确认的状态；刷新和失败不能覆盖上次有效结果。
            _uiState.update {
                if (it.isOnline && it.accountOverview?.username?.substringBefore("@")?.trim() == account.substringBefore("@").trim()) {
                    it.copy(seamlessStatus = result.info.status)
                } else it
            }
        }
        _seamlessState.update {
            when (result) {
                is SeamlessLoadResult.Success -> it.copy(info = result.info, isLoading = false, error = "")
                is SeamlessLoadResult.Failure -> it.copy(isLoading = false, error = result.message)
            }
        }
    }

    fun performSeamlessAction(action: SeamlessAction) {
        val state = _seamlessState.value
        val info = state.info ?: return
        if (!state.canManage || seamlessMutationJob?.isActive == true) return
        val epoch = seamlessEpoch
        _seamlessState.update { it.copy(isSubmitting = true, operationMessage = "") }
        // 操作由 ViewModel 持有；退出页面不会中断已经提交的请求。
        seamlessMutationJob = viewModelScope.launch {
            var refreshNetwork = false
            try {
                accountOperationMutex.withLock {
                    if (epoch != seamlessEpoch) return@withLock
                    val authenticated = network.fetchLoginStatus(
                        preferredMethod = preferredWifiLoginMethod(_uiState.value.wifiName)
                    )
                    if (epoch != seamlessEpoch) return@withLock
                    if (!authenticated.isLoggedIn || authenticated.uid.substringBefore("@").trim() != info.username.substringBefore("@").trim()) {
                        _uiState.update { it.copy(seamlessStatus = SeamlessStatus.Unknown) }
                        _seamlessState.update { it.copy(info = null, error = strings.get(R.string.seamless_session_expired)) }
                        return@withLock
                    }
                    val result = selfService.performSeamlessAction(info, action)
                    if (epoch != seamlessEpoch) return@withLock
                    refreshNetwork = action !is SeamlessAction.Bind && result !is SeamlessActionResult.Failure
                    val message = when (result) {
                        SeamlessActionResult.Success -> strings.get(R.string.seamless_operation_success)
                        is SeamlessActionResult.Failure -> result.message
                        is SeamlessActionResult.Indeterminate -> result.message
                    }
                    _seamlessState.update { it.copy(operationMessage = message) }
                    loadSeamlessLocked(info.username, epoch)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (epoch == seamlessEpoch) _seamlessState.update {
                    it.copy(operationMessage = strings.get(R.string.seamless_operation_failed))
                }
            } finally {
                seamlessMutationJob = null
                _seamlessState.update { it.copy(isSubmitting = false) }
                // 关闭无感知或解绑当前设备可能让门户离线，需要同步主页认证状态。
                if (epoch == seamlessEpoch && refreshNetwork) refreshStatus()
                else if (epoch != seamlessEpoch) refreshSeamlessInfo()
            }
        }
    }

    private val updateCoordinator = AppUpdateCoordinator(
        scope = viewModelScope,
        strings = strings,
        settings = settings,
        updates = updates,
        hasValidatedInternet = network::hasValidatedInternet,
        currentVersion = BuildConfig.VERSION_NAME,
        currentVersionCode = BuildConfig.VERSION_CODE,
        currentTimeMillis = currentTimeMillis,
        automaticCheckDelayMs = AUTOMATIC_UPDATE_CHECK_DELAY_MS,
        automaticCheckIntervalMs = AUTOMATIC_UPDATE_CHECK_INTERVAL_MS
    )
    val updateState: StateFlow<UpdateState> = updateCoordinator.state
    val updateMessages: SharedFlow<String> = updateCoordinator.messages
    val updateInstallation = updateCoordinator.installation

    fun setUpdateForeground(value: Boolean) = updateCoordinator.setForeground(value)
    fun cancelAutomaticUpdateInstall() = updateCoordinator.cancelAutomaticInstall()
    fun consumeUpdateInstallation() = updateCoordinator.consumeInstallation()
    fun resumeUpdateInstallation(versionCode: Int) = updateCoordinator.resumeInstallation(versionCode)
    fun showUpdateMessage(resource: Int) = updateCoordinator.message(resource)
    suspend fun validatedUpdateDownload(update: top.steins.autologin.network.update.UpdateInfo) =
        updateCoordinator.validatedDownload(update)

    private var refreshJob: Job? = null
    private var networkRefreshDebounceJob: Job? = null
    private var refreshGeneration = 0L
    private var versionTapCount = 0
    // 只读取启动时的开关；本次运行中开启设置不会立即触发登录。
    private var startupAutoLoginPending = autoLoginOnStartup.value

    init {
        HttpLogStorage.setEnabled(httpLogEnabled.value)

        network.observeDefaultNetworkChanges()
            .conflate()
            .collectInViewModel { change -> scheduleNetworkRefresh(change) }

        settings.targetWifiConfigChanges.collectInViewModel { change ->
            startRefresh(
                trigger = AccountInfoRefreshTrigger.TargetWifiConfigurationChanged(change),
                clearSession = false,
                attempts = 1,
                initialDelayMs = 0
            )
        }
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

    suspend fun saveCredentials(username: String, password: String): CredentialSaveResult =
        settings.saveCredentials(username, password)

    suspend fun selectAccount(username: String) = settings.selectAccount(username)

    suspend fun removeAccount(username: String) = settings.removeAccount(username)

    fun addTargetWifi(ssid: String) = settings.addTargetWifi(ssid)

    fun removeTargetWifi(ssid: String) = settings.removeTargetWifi(ssid)

    fun saveAppearanceMode(mode: AppearanceMode) = settings.saveAppearanceMode(mode)

    fun setAutoLoginOnStartup(enabled: Boolean) {
        settings.setAutoLoginOnStartup(enabled)
        if (!enabled) startupAutoLoginPending = false
    }

    /** 返回 true 表示本次点击刚刚解锁了 HTTP 日志。 */
    fun onAboutVersionClicked(): Boolean {
        if (httpLogEnabled.value) return false
        versionTapCount += 1
        if (versionTapCount < HTTP_LOG_UNLOCK_TAP_COUNT) return false

        settings.setHttpLogEnabled(true)
        HttpLogStorage.setEnabled(true)
        versionTapCount = 0
        return true
    }

    fun disableHttpLog() {
        settings.setHttpLogEnabled(false)
        HttpLogStorage.setEnabled(false)
        versionTapCount = 0
    }

    fun acknowledgeCredentialReset() = settings.acknowledgeCredentialReset()

    fun clearHttpLogs() = HttpLogStorage.clear()

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

    fun checkForUpdates(manual: Boolean = false) = updateCoordinator.check(manual)

    fun downloadAvailableUpdate() = updateCoordinator.downloadAvailable()

    private fun scheduleNetworkRefresh(change: DefaultNetworkChange) {
        networkRefreshDebounceJob?.cancel()
        networkRefreshDebounceJob = viewModelScope.launch {
            delay(NETWORK_REFRESH_DEBOUNCE_MS)
            startRefresh(
                trigger = AccountInfoRefreshTrigger.DefaultNetworkChanged(change),
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
        invalidateSeamless(keepInfo = true)
        val generation = refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                settingsReady.await()
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
                if (generation == refreshGeneration && seamlessLoadJob?.isActive != true) {
                    _seamlessState.update { it.copy(isLoading = false) }
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
        invalidateSeamless()
        startupAutoLoginPending = false
        updateCoordinator.cancelForLogin()
        _uiState.update { it.copy(isLoggingIn = true, loginMessage = null) }
        return viewModelScope.async {
            try {
                settingsReady.await()
                cancelPendingRefreshForLogin()
                _uiState.update { it.copy(isAccountInfoLoading = false) }
                val result = accountOperationMutex.withLock { loginOnCurrentNetwork() }
                val message = when (result) {
                    LoginResult.Success -> {
                        confirmLogin()
                        updateCoordinator.scheduleAutomaticCheck()
                        strings.get(R.string.home_login_success_fetching)
                    }
                    LoginResult.AlreadyOnline -> {
                        confirmLogin(knownLoggedIn = true)
                        updateCoordinator.scheduleAutomaticCheck()
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
            settings.addAutoDetectedTargetWifi(networkInfo.wifiName)
        }
        // 后台保存/切换账号时，读取同一份快照，避免把不同账号的用户名和密码拼在一起。
        val account = settings.readActiveAccount()
        val username = account?.username.orEmpty()
        val password = account?.password.orEmpty()
        return when {
            username.isBlank() || password.isBlank() ->
                LoginResult.Failure(strings.get(R.string.login_no_credentials))
            networkInfo.isCellular ->
                LoginResult.Failure(strings.get(R.string.login_cellular_blocked))
            !networkInfo.isWifi || networkInfo.wifiName !in settings.targetWifis.value ->
                LoginResult.Failure(strings.get(R.string.login_wrong_network))
            else -> network.performLogin(
                username,
                password,
                preferredMethod = preferredWifiLoginMethod(networkInfo.wifiName)
            )
        }
    }

    private fun preferredWifiLoginMethod(ssid: String): WifiLoginMethod? =
        settings.getWifiLoginMethod(ssid)
            ?: WifiLoginMethod.WLGN.takeIf { ssid == "bjut_wifi" }

    private suspend fun tryStartupAutoLogin(generation: Long): RefreshAttemptOutcome {
        if (!startupAutoLoginPending) return RefreshAttemptOutcome.Complete
        startupAutoLoginPending = false
        if (!autoLoginOnStartup.value || !_uiState.value.hasLocationPermission ||
            username.value.isBlank() || password.value.isBlank()
        ) {
            return RefreshAttemptOutcome.Complete
        }
        updateState(generation) { copy(isAccountInfoLoading = true) }
        updateCoordinator.cancelForLogin()
        return when (val result = loginOnCurrentNetwork()) {
            LoginResult.Success -> {
                updateCoordinator.scheduleAutomaticCheck()
                RefreshAttemptOutcome.LoginSucceeded
            }
            LoginResult.AlreadyOnline -> {
                updateCoordinator.scheduleAutomaticCheck()
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

    suspend fun logoutDevice(
        sessionId: String,
        ipAddress: String,
        macAddress: String
    ): DeviceLogoutResult =
        accountOperationMutex.withLock {
            selfService.logoutDevice(sessionId, ipAddress, macAddress)
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
            invalidateSeamless()
        }
        if (networkInfo.isWifi) {
            settings.addAutoDetectedTargetWifi(networkInfo.wifiName)
        }
        val isTargetWifi = networkInfo.isWifi &&
                networkInfo.wifiName in settings.targetWifis.value

        if (!isTargetWifi) {
            // 初次权限弹窗期间可能读不到 SSID，保留机会直到权限结果返回。
            if (_uiState.value.hasLocationPermission || !networkInfo.isWifi) {
                startupAutoLoginPending = false
            }
            selfService.clearSession()
            if (generation == refreshGeneration) invalidateSeamless()
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
                settings.saveWifiLoginMethod(networkInfo.wifiName, status.verifiedMethod)
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
            if (generation == refreshGeneration) invalidateSeamless()
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
            if (generation == refreshGeneration) invalidateSeamless()
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
            invalidateSeamless()
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

    private fun updateState(generation: Long, transform: AppUiState.() -> AppUiState) {
        if (generation == refreshGeneration) {
            _uiState.update { it.transform() }
        }
    }

    private fun <T> Flow<T>.collectInViewModel(onValue: (T) -> Unit) {
        viewModelScope.launch {
            collect { onValue(it) }
        }
    }

    override fun onCleared() {
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
        const val AUTOMATIC_UPDATE_CHECK_DELAY_MS = 3_000L
        const val AUTOMATIC_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
        const val HTTP_LOG_UNLOCK_TAP_COUNT = 5
    }
}
