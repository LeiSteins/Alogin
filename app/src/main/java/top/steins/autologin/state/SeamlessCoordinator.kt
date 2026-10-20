package top.steins.autologin.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steins.autologin.AppStrings
import top.steins.autologin.HomeUiState
import top.steins.autologin.R
import top.steins.autologin.SeamlessUiState
import top.steins.autologin.data.WifiLoginMethod
import top.steins.autologin.network.NetworkEnvironment
import top.steins.autologin.network.SelfServiceGateway
import top.steins.autologin.network.SeamlessAction
import top.steins.autologin.network.SeamlessActionResult
import top.steins.autologin.network.SeamlessLoadResult
import top.steins.autologin.network.SeamlessStatus

/** 与认证协调器共享会话、锁和主页状态，统一拦截过期结果。 */
internal class SeamlessCoordinator(
    private val scope: CoroutineScope,
    private val strings: AppStrings,
    private val selfService: SelfServiceGateway,
    private val network: NetworkEnvironment,
    private val accountOperationMutex: Mutex,
    private val homeState: MutableStateFlow<HomeUiState>,
    private val preferredWifiLoginMethod: (String) -> WifiLoginMethod?,
    private val refreshNetworkStatus: () -> Unit
) {
    private val _seamlessState = MutableStateFlow(SeamlessUiState())
    val state: StateFlow<SeamlessUiState> = _seamlessState.asStateFlow()
    val isLoading: Boolean get() = seamlessLoadJob?.isActive == true

    private var seamlessEpoch = 0L
    private var seamlessLoadJob: Job? = null
    private var seamlessMutationJob: Job? = null

    fun invalidate(keepInfo: Boolean = false) {
        seamlessEpoch += 1
        seamlessLoadJob?.cancel()
        _seamlessState.value = if (keepInfo) {
            _seamlessState.value.copy(isLoading = true)
        } else {
            SeamlessUiState(isSubmitting = seamlessMutationJob?.isActive == true)
        }
    }

    fun finishRefresh() {
        _seamlessState.update { it.copy(isLoading = false) }
    }

    fun refresh() {
        if (seamlessMutationJob?.isActive == true || seamlessLoadJob?.isActive == true) return
        if (homeState.value.isAccountInfoLoading) return
        val account = homeState.value.accountOverview?.username
        if (!homeState.value.isOnline || account.isNullOrBlank()) {
            _seamlessState.value = SeamlessUiState(error = strings.get(R.string.seamless_requires_login))
            return
        }
        val epoch = seamlessEpoch
        _seamlessState.update { it.copy(isLoading = true) }
        seamlessLoadJob = scope.launch {
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
            homeState.update {
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

    fun perform(action: SeamlessAction) {
        val state = _seamlessState.value
        val info = state.info ?: return
        if (!state.canManage || seamlessMutationJob?.isActive == true) return
        val epoch = seamlessEpoch
        _seamlessState.update { it.copy(isSubmitting = true, operationMessage = "") }
        // 操作由 ViewModel 持有；退出页面不会中断已经提交的请求。
        seamlessMutationJob = scope.launch {
            var refreshNetwork = false
            try {
                accountOperationMutex.withLock {
                    if (epoch != seamlessEpoch) return@withLock
                    val authenticated = network.fetchLoginStatus(
                        preferredMethod = preferredWifiLoginMethod(homeState.value.wifiName)
                    )
                    if (epoch != seamlessEpoch) return@withLock
                    if (!authenticated.isLoggedIn || authenticated.uid.substringBefore("@").trim() != info.username.substringBefore("@").trim()) {
                        homeState.update { it.copy(seamlessStatus = SeamlessStatus.Unknown) }
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
                if (epoch == seamlessEpoch && refreshNetwork) refreshNetworkStatus()
                else if (epoch != seamlessEpoch) refresh()
            }
        }
    }

}
