package top.steins.autologin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.steins.autologin.BuildConfig
import top.steins.autologin.R
import top.steins.autologin.network.update.UpdateInfo
import top.steins.autologin.network.update.UpdateDownloadState
import top.steins.autologin.network.update.UpdateDownloadFailure

@Composable
fun UpdateDialog(
    update: UpdateInfo,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    download: UpdateDownloadState = UpdateDownloadState.None
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title, update.version)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (download == UpdateDownloadState.None) {
                    Text(stringResource(R.string.update_dialog_message, BuildConfig.VERSION_NAME))
                } else {
                    Text(updateDownloadSummary(download))
                }
                if (download is UpdateDownloadState.Downloading) {
                    val progress = download.progress
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (download == UpdateDownloadState.Ready) {
                    Text(stringResource(R.string.update_install_description))
                }
                if (update.releaseNotes.isNotBlank()) {
                    Text(
                        stringResource(R.string.update_release_notes),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(update.releaseNotes)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload, enabled = download !is UpdateDownloadState.Downloading) {
                Text(stringResource(when (download) {
                    UpdateDownloadState.None -> R.string.update_download
                    is UpdateDownloadState.Downloading -> R.string.update_downloading_action
                    UpdateDownloadState.Ready -> R.string.update_install
                    is UpdateDownloadState.Failed -> R.string.update_download_retry
                }))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
        }
    )
}

@Composable
fun updateDownloadSummary(download: UpdateDownloadState): String = when (download) {
    UpdateDownloadState.None -> ""
    is UpdateDownloadState.Downloading -> when {
        download.waitingForNetwork -> stringResource(R.string.update_waiting_network)
        download.progress != null -> stringResource(R.string.update_download_progress, download.progress)
        else -> stringResource(R.string.update_downloading_action)
    }
    UpdateDownloadState.Ready -> stringResource(R.string.update_ready)
    is UpdateDownloadState.Failed -> stringResource(when (download.reason) {
        UpdateDownloadFailure.DOWNLOAD -> R.string.update_download_failed
        UpdateDownloadFailure.STORAGE -> R.string.update_storage_failed
        UpdateDownloadFailure.MISSING_FILE -> R.string.update_file_missing
        UpdateDownloadFailure.INVALID_APK -> R.string.update_invalid_apk
    })
}
