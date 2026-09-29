package top.steins.autologin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
import top.steins.autologin.network.AccountOverviewResult
import top.steins.autologin.network.DefaultNetworkChange
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.network.HttpLogEntry
import top.steins.autologin.network.HttpLogStorage
import top.steins.autologin.network.LoginResult
import top.steins.autologin.network.NetworkEnvironment
import top.steins.autologin.network.SelfServiceGateway
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

    val username: StateFlow<String> = settings.username
    val password: StateFlow<String> = settings.password
    val targetWifis: StateFlow<List<String>> = settings.targetWifis
    val appearanceMode: StateFlow<AppearanceMode> = settings.appearanceMode
    val httpLogEnabled: StateFlow<Boolean> = settings.httpLogEnabled
    val credentialResetPending: StateFlow<Boolean> = settings.credentialResetPending
    val httpLogs: StateFlow<List<HttpLogEntry>> = HttpLogStorage.logs

    private val accountOperationMutex = Mutex()

    private val _uiState = MutableStateFlow(
        AppUiState(
            wifiName = strings.get(R.string.loading),
            ipAddress = strings.get(R.string.loading),
            hasLocationPermission = hasLocationPermission()
        )
    )
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

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

    private var refreshJob: Job? = null
    private var networkRefreshDebounceJob: Job? = null
    private var refreshGeneration = 0L
    private var versionTapCount = 0

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

    fun saveCredentials(username: String, password: String): CredentialSaveResult =
        settings.saveCredentials(username, password)

    fun addTargetWifi(ssid: String) = settings.addTargetWifi(ssid)

    fun removeTargetWifi(ssid: String) = settings.removeTargetWifi(ssid)

    fun saveAppearanceMode(mode: AppearanceMode) = settings.saveAppearanceMode(mode)

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

    fun confirmLogin() {
        startRefresh(
            trigger = AccountInfoRefreshTrigger.LoginConfirmation,
            clearSession = false,
            attempts = LOGIN_CONFIRMATION_ATTEMPTS,
            initialDelayMs = LOGIN_CONFIRMATION_INITIAL_DELAY_MS
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
        initialDelayMs: Long
    ) {
        refreshGeneration += 1
        val generation = refreshGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (initialDelayMs > 0) delay(initialDelayMs)
            repeat(attempts) { attempt ->
                HttpLogStorage.logAccountInfoRefresh(
                    trigger.description(strings, attempt + 1, attempts)
                )
                val outcome = accountOperationMutex.withLock {
                    refreshStatusInternal(
                        generation = generation,
                        clearSession = clearSession && attempt == 0,
                        showFinalError = attempt == attempts - 1
                    )
                }
                if (outcome == RefreshAttemptOutcome.Complete) {
                    return@launch
                }
                if (attempt < attempts - 1) delay(REFRESH_RETRY_DELAY_MS)
            }
        }
    }

    suspend fun login(): LoginResult {
        updateCoordinator.cancelForLogin()
        cancelPendingRefreshForLogin()
        val result = accountOperationMutex.withLock {
            val networkInfo = network.awaitCurrentNetworkInfo(
                canReadWifiName = _uiState.value.hasLocationPermission
            )
            if (networkInfo.isWifi) {
                settings.addAutoDetectedTargetWifi(networkInfo.wifiName)
            }
            val username = settings.username.value
            val password = settings.password.value
            when {
                username.isBlank() || password.isBlank() -> {
                    LoginResult.Failure(strings.get(R.string.login_no_credentials))
                }

                networkInfo.isCellular -> {
                    LoginResult.Failure(strings.get(R.string.login_cellular_blocked))
                }

                !networkInfo.isWifi || networkInfo.wifiName !in settings.targetWifis.value -> {
                    LoginResult.Failure(strings.get(R.string.login_wrong_network))
                }

                else -> network.performLogin(username, password)
            }
        }
        if (result is LoginResult.Success) updateCoordinator.scheduleAutomaticCheck()
        return result
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
        showFinalError: Boolean
    ): RefreshAttemptOutcome {
        if (clearSession) selfService.clearSession()

        val networkInfo = network.fetchCurrentNetworkInfo(
            canReadWifiName = _uiState.value.hasLocationPermission
        )
        if (networkInfo.isWifi) {
            settings.addAutoDetectedTargetWifi(networkInfo.wifiName)
        }
        val isTargetWifi = networkInfo.isWifi &&
                networkInfo.wifiName in settings.targetWifis.value

        if (!isTargetWifi) {
            selfService.clearSession()
            updateState(generation) {
                copy(
                    wifiName = networkInfo.wifiName,
                    ipAddress = networkInfo.ipAddress,
                    isOnline = false,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = "",
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
                    networkStatusError = ""
                )
            }
            return RefreshAttemptOutcome.Complete
        }

        updateState(generation) {
            copy(
                wifiName = networkInfo.wifiName,
                ipAddress = networkInfo.ipAddress,
                isOnline = false,
                accountOverview = null,
                isAccountInfoLoading = false,
                accountInfoError = "",
                isDeviceListAvailable = false,
                canLogoutDevices = false,
                networkStatusError = ""
            )
        }

        val status = network.fetchLoginStatus()
        if (!status.isLoggedIn) {
            selfService.clearSession()
            updateState(generation) {
                copy(
                    isOnline = false,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = "",
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
                    networkStatusError = if (showFinalError) status.error else ""
                )
            }
            return if (status.error.isBlank()) {
                // 已成功访问校园网状态页，只是尚未认证；继续检查只会阻塞用户登录。
                RefreshAttemptOutcome.Complete
            } else {
                RefreshAttemptOutcome.RetryableFailure
            }
        }

        // 自助服务账号必须与当前校园网认证账号一致，直接采用 lgn 注销页返回的 uid。
        val username = status.uid
        if (username.isBlank()) {
            selfService.clearSession()
            updateState(generation) {
                copy(
                    isOnline = true,
                    accountOverview = null,
                    isAccountInfoLoading = false,
                    accountInfoError = strings.get(R.string.account_info_fetch_failed),
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
                    networkStatusError = ""
                )
            }
            return RefreshAttemptOutcome.Complete
        }

        updateState(generation) {
            copy(
                isOnline = true,
                isAccountInfoLoading = true,
                accountInfoError = "",
                isDeviceListAvailable = false,
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
                        isAccountInfoLoading = false,
                        accountInfoError = result.warningMessage,
                        isDeviceListAvailable = result.isDeviceListAvailable,
                        canLogoutDevices = result.canLogoutDevices
                    )
                }
                RefreshAttemptOutcome.Complete
            }

            is AccountOverviewResult.Failure -> {
                updateState(generation) {
                    copy(
                        isOnline = true,
                        accountOverview = null,
                        isAccountInfoLoading = result.isRetryable && !showFinalError,
                        accountInfoError = if (!result.isRetryable || showFinalError) {
                            result.message
                        } else {
                            ""
                        },
                        isDeviceListAvailable = false,
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
