package top.steins.autologin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.steins.autologin.data.SettingsGateway
import top.steins.autologin.network.update.SemanticVersion
import top.steins.autologin.network.update.UpdateDownloadResult
import top.steins.autologin.network.update.UpdateGateway
import top.steins.autologin.network.update.UpdateState

/** 独立管理更新检查、节流和下载消息，避免这些状态挤入网络认证协调器。 */
internal class AppUpdateCoordinator(
    private val scope: CoroutineScope,
    private val strings: AppStrings,
    private val settings: SettingsGateway,
    private val updates: UpdateGateway,
    private val hasValidatedInternet: () -> Boolean,
    private val currentVersion: String,
    private val currentVersionCode: Int,
    private val currentTimeMillis: () -> Long,
    private val automaticCheckDelayMs: Long,
    private val automaticCheckIntervalMs: Long
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var checkJob: Job? = null

    fun check(manual: Boolean) {
        checkJob?.cancel()
        checkJob = scope.launch {
            performCheck(manual)
        }
    }

    fun scheduleAutomaticCheck() {
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(automaticCheckDelayMs)
            if (!hasValidatedInternet()) return@launch

            val now = currentTimeMillis()
            val lastCheckAt = settings.getLastUpdateCheckAt()
            val isDue = lastCheckAt <= 0L ||
                    now < lastCheckAt ||
                    now - lastCheckAt >= automaticCheckIntervalMs
            if (isDue) performCheck(manual = false)
        }
    }

    fun cancelForLogin() {
        checkJob?.cancel()
        checkJob = null
        if (_state.value is UpdateState.Checking) {
            _state.value = UpdateState.Idle
        }
    }

    fun downloadAvailable() {
        val update = (_state.value as? UpdateState.Available)?.update ?: return
        val message = when (updates.downloadUpdate(update)) {
            UpdateDownloadResult.Enqueued -> R.string.update_download_enqueued
            UpdateDownloadResult.OpenedInBrowser -> R.string.update_opened_in_browser
            UpdateDownloadResult.Failed -> R.string.update_download_failed
        }
        _messages.tryEmit(strings.get(message))
    }

    private suspend fun performCheck(manual: Boolean) {
        _state.value = UpdateState.Checking
        val checkedAt = currentTimeMillis()
        try {
            val update = updates.fetchLatestUpdate(currentVersion)
            settings.setLastUpdateCheckAt(checkedAt)
            val result = if (SemanticVersion.isNewer(update.version, currentVersion) &&
                update.versionCode > currentVersionCode
            ) {
                UpdateState.Available(update)
            } else {
                UpdateState.UpToDate(update.version)
            }
            _state.value = result
            if (manual) {
                val message = when (result) {
                    is UpdateState.Available -> strings.get(
                        R.string.update_found,
                        result.update.version
                    )
                    is UpdateState.UpToDate -> strings.get(R.string.update_latest_toast)
                    else -> null
                }
                message?.let { _messages.emit(it) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            settings.setLastUpdateCheckAt(checkedAt)
            _state.value = UpdateState.Error(
                error.message ?: strings.get(R.string.update_check_failed)
            )
            if (manual) {
                _messages.emit(strings.get(R.string.update_failed_toast))
            }
        }
    }
}
