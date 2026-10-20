package top.steins.autologin.state

import top.steins.autologin.domain.model.UpdateDownloadState
import top.steins.autologin.domain.model.UpdateInfo

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latestVersion: String) : UpdateState
    data class Available(
        val update: UpdateInfo,
        val download: UpdateDownloadState = UpdateDownloadState.None
    ) : UpdateState
    data class Error(val message: String) : UpdateState
}
