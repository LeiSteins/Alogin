package top.steins.autologin.state

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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steins.autologin.AppStrings
import top.steins.autologin.R
import top.steins.autologin.data.UpdateCheckStore
import top.steins.autologin.network.update.SemanticVersion
import top.steins.autologin.network.update.UpdateDownloadState
import top.steins.autologin.network.update.UpdateDownloadFailure
import top.steins.autologin.network.update.UpdateInfo
import top.steins.autologin.network.update.UpdateTransfer
import top.steins.autologin.network.update.UpdateGateway
import top.steins.autologin.network.update.UpdateState

/** 独立管理更新检查、节流和下载消息，避免这些状态挤入网络认证协调器。 */
class UpdateStateHolder(
    private val scope: CoroutineScope,
    private val strings: AppStrings,
    private val checkStore: UpdateCheckStore,
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
    private var progressJob: Job? = null
    private var failedCheckCount = 0
    private val downloadMutex = Mutex()
    private var foreground = false
    private var installWhenReady = false
    private val _installation = MutableStateFlow<UpdateInfo?>(null)
    val installation: StateFlow<UpdateInfo?> = _installation.asStateFlow()

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        observationJob?.cancel()
        if (!value) {
            progressJob?.cancel()
            progressJob = null
            cancelAutomaticInstall()
            return
        }
        observationJob = scope.launch {
            updates.observeDownloadChanges().onStart { emit(Unit) }.collect {
                downloadMutex.withLock {
                    updates.refreshDownload()?.let(::publishTransfer)
                }
            }
        }
        updateProgressPolling()
    }

    private fun updateProgressPolling() {
        val downloading = (_state.value as? UpdateState.Available)?.download is UpdateDownloadState.Downloading
        if (!foreground || !downloading) {
            progressJob?.cancel()
            progressJob = null
            return
        }
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (true) {
                delay(DOWNLOAD_PROGRESS_INTERVAL_MS)
                downloadMutex.withLock {
                    val available = _state.value as? UpdateState.Available ?: return@launch
                    val transfer = updates.refreshDownload() ?: UpdateTransfer(
                        available.update, UpdateDownloadState.Failed(UpdateDownloadFailure.MISSING_FILE)
                    )
                    publishTransfer(transfer)
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
            val lastCheckAt = checkStore.getLastUpdateCheckAt()
            val retryAt = checkStore.getUpdateCheckRetryAt()
            val isDue = if (retryAt > 0L) {
                // 时钟回拨超过最大退避窗口时允许重新检查。
                now >= retryAt || retryAt - now > MAXIMUM_CHECK_RETRY_DELAY_MS
            } else {
                lastCheckAt <= 0L || now < lastCheckAt || now - lastCheckAt >= automaticCheckIntervalMs
            }
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
        updateProgressPolling()
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
            checkStore.setLastUpdateCheckAt(checkedAt)
            checkStore.setUpdateCheckRetryAt(0L)
            failedCheckCount = 0
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
            val retryDelay = (INITIAL_CHECK_RETRY_DELAY_MS * (1L shl failedCheckCount.coerceAtMost(4)))
                .coerceAtMost(MAXIMUM_CHECK_RETRY_DELAY_MS)
            failedCheckCount = (failedCheckCount + 1).coerceAtMost(4)
            checkStore.setUpdateCheckRetryAt(currentTimeMillis() + retryDelay)
            _state.value = UpdateState.Error(
                error.message ?: strings.get(R.string.update_check_failed)
            )
            if (manual) {
                _messages.emit(strings.get(R.string.update_failed_toast))
            }
        }
    }

    companion object {
        const val AUTOMATIC_UPDATE_CHECK_DELAY_MS = 3_000L
        const val AUTOMATIC_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
        const val DOWNLOAD_PROGRESS_INTERVAL_MS = 1_000L
        const val INITIAL_CHECK_RETRY_DELAY_MS = 5L * 60L * 1_000L
        const val MAXIMUM_CHECK_RETRY_DELAY_MS = 60L * 60L * 1_000L
    }
}
