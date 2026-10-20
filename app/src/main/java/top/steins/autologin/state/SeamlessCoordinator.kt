package top.steins.autologin.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.steins.autologin.AppStrings
import top.steins.autologin.R
import top.steins.autologin.SeamlessUiState
import top.steins.autologin.domain.model.SeamlessAction
import top.steins.autologin.domain.model.SeamlessActionResult
import top.steins.autologin.domain.model.SeamlessLoadResult
import top.steins.autologin.domain.model.SeamlessStatus
import top.steins.autologin.domain.model.WifiLoginMethod
import top.steins.autologin.domain.port.LoginStatusReader
import top.steins.autologin.domain.port.SeamlessGateway

/** 通过只读快照使用会话并上报结果；主页写入和操作串行化由会话协调器负责。 */
internal class SeamlessCoordinator(
    private val scope: CoroutineScope,
    private val strings: AppStrings,
    private val selfService: SeamlessGateway,
    private val authentication: LoginStatusReader,
    private val session: SeamlessSessionAccess,
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
        val snapshot = session.snapshot()
        if (snapshot.isRefreshing) return
        if (!snapshot.isOnline || snapshot.username.isBlank()) {
            _seamlessState.value = SeamlessUiState(error = strings.get(R.string.seamless_requires_login))
            return
        }
        val epoch = seamlessEpoch
        _seamlessState.update { it.copy(isLoading = true) }
        seamlessLoadJob = scope.launch {
            try {
                session.withSession(snapshot) {
                    if (epoch == seamlessEpoch) loadSeamlessLocked(snapshot, epoch)
                }
            } finally {
                if (epoch == seamlessEpoch) _seamlessState.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadSeamlessLocked(snapshot: CampusSessionSnapshot, epoch: Long) {
        val result = selfService.loadSeamlessInfo(snapshot.username)
        if (epoch != seamlessEpoch || !session.isCurrent(snapshot)) return
        if (result is SeamlessLoadResult.Success && result.info.status != SeamlessStatus.Unknown &&
            result.info.username.substringBefore("@").trim() == snapshot.username.substringBefore("@").trim()
        ) {
            // 主页只保存服务端已确认的状态；刷新和失败不能覆盖上次有效结果。
            session.reportStatus(snapshot, result.info.status)
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
        val snapshot = session.snapshot()
        val epoch = seamlessEpoch
        _seamlessState.update { it.copy(isSubmitting = true, operationMessage = "") }
        // 操作由 ViewModel 持有；退出页面不会中断已经提交的请求。
        seamlessMutationJob = scope.launch {
            var refreshNetwork = false
            try {
                session.withSession(snapshot) {
                    if (epoch != seamlessEpoch) return@withSession
                    val authenticated = authentication.fetchLoginStatus(
                        preferredMethod = preferredWifiLoginMethod(snapshot.wifiName)
                    )
                    if (epoch != seamlessEpoch || !session.isCurrent(snapshot)) return@withSession
                    if (!authenticated.isLoggedIn || authenticated.uid.substringBefore("@").trim() != info.username.substringBefore("@").trim()) {
                        session.reportStatus(snapshot, SeamlessStatus.Unknown)
                        _seamlessState.update { it.copy(info = null, error = strings.get(R.string.seamless_session_expired)) }
                        return@withSession
                    }
                    val result = selfService.performSeamlessAction(info, action)
                    if (epoch != seamlessEpoch || !session.isCurrent(snapshot)) return@withSession
                    refreshNetwork = action !is SeamlessAction.Bind && result !is SeamlessActionResult.Failure
                    val message = when (result) {
                        SeamlessActionResult.Success -> strings.get(R.string.seamless_operation_success)
                        is SeamlessActionResult.Failure -> result.message
                        is SeamlessActionResult.Indeterminate -> result.message
                    }
                    _seamlessState.update { it.copy(operationMessage = message) }
                    loadSeamlessLocked(snapshot, epoch)
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
