package top.steins.autologin.network.update

/** 下载状态独立于版本检查；恢复任务时无需重新访问更新服务器。 */
sealed interface UpdateDownloadState {
    data object None : UpdateDownloadState
    data class Downloading(val progress: Int? = null, val waitingForNetwork: Boolean = false) : UpdateDownloadState
    data object Ready : UpdateDownloadState
    data class Failed(val reason: UpdateDownloadFailure) : UpdateDownloadState
}

enum class UpdateDownloadFailure { DOWNLOAD, STORAGE, MISSING_FILE, INVALID_APK }

data class UpdateTransfer(val update: UpdateInfo, val state: UpdateDownloadState)

internal fun matchesUpdateArchive(
    packageName: String?,
    versionName: String?,
    versionCode: Long,
    expectedPackage: String,
    installedVersionCode: Long,
    update: UpdateInfo
): Boolean = packageName == expectedPackage && versionName == update.version &&
    versionCode == update.versionCode.toLong() && versionCode > installedVersionCode
