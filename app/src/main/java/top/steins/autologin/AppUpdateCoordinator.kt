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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steins.autologin.data.SettingsGateway
import top.steins.autologin.network.update.SemanticVersion
import top.steins.autologin.network.update.UpdateDownloadState
import top.steins.autologin.network.update.UpdateDownloadFailure
import top.steins.autologin.network.update.UpdateInfo
import top.steins.autologin.network.update.UpdateTransfer
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
    private var downloadJob: Job? = null
    private var observationJob: Job? = null
    private val downloadMutex = Mutex()
    private var foreground = false
    private var installWhenReady = false
    private val _installation = MutableStateFlow<UpdateInfo?>(null)
    val installation: StateFlow<UpdateInfo?> = _installation.asStateFlow()

    fun setForeground(value: Boolean) {
        foreground = value
        observationJob?.cancel()
        if (!value) {
            cancelAutomaticInstall()
            return
        }
        observationJob = scope.launch {
            updates.observeDownloadChanges().collect {
                downloadMutex.withLock {
                    updates.refreshDownload()?.let(::publishTransfer)
                }
            }
        }
    }

    fun cancelAutomaticInstall() {
        installWhenReady = false
        _installation.value = null
    }

    fun consumeInstallation() {
        _installation.value = null
    }

    fun message(resource: Int) {
        _messages.tryEmit(strings.get(resource))
    }

    suspend fun validatedDownload(update: UpdateInfo): java.io.File? = downloadMutex.withLock {
        updates.validatedDownload(update).also { file ->
            if (file == null) {
                publishTransfer(UpdateTransfer(update, UpdateDownloadState.Failed(UpdateDownloadFailure.INVALID_APK)))
                message(R.string.update_invalid_apk)
            }
        }
    }

    fun resumeInstallation(versionCode: Int) {
        scope.launch {
            downloadMutex.withLock {
                val transfer = updates.refreshDownload() ?: return@withLock
                publishTransfer(transfer)
                if (foreground && transfer.update.versionCode == versionCode &&
                    transfer.state == UpdateDownloadState.Ready
                ) _installation.value = transfer.update
            }
        }
    }

    fun check(manual: Boolean) {
        if (hasDownload()) return
        checkJob?.cancel()
        checkJob = scope.launch {
            performCheck(manual)
        }
    }

    fun scheduleAutomaticCheck() {
        if (hasDownload()) return
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(automaticCheckDelayMs)
            if (!hasValidatedInternet() || hasDownload()) return@launch

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
        val available = _state.value as? UpdateState.Available ?: return
        if (downloadJob?.isActive == true || available.download is UpdateDownloadState.Downloading) return
        if (available.download == UpdateDownloadState.Ready) {
            if (foreground) _installation.value = available.update
            return
        }
        checkJob?.cancel()
        installWhenReady = foreground
        _state.value = available.copy(download = UpdateDownloadState.Downloading())
        downloadJob = scope.launch {
            downloadMutex.withLock {
                publishTransfer(updates.downloadUpdate(available.update))
            }
        }
    }

    private fun hasDownload(): Boolean = (_state.value as? UpdateState.Available)?.download.let {
        it != null && it != UpdateDownloadState.None
    }

    private fun publishTransfer(transfer: UpdateTransfer) {
        checkJob?.cancel()
        _state.value = UpdateState.Available(transfer.update, transfer.state)
        if (transfer.state == UpdateDownloadState.Ready && installWhenReady && foreground) {
            installWhenReady = false
            _installation.value = transfer.update
        }
        if (transfer.state is UpdateDownloadState.Failed) installWhenReady = false
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
