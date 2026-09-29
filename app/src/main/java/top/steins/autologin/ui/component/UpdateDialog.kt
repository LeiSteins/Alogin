package top.steins.autologin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.steins.autologin.BuildConfig
import top.steins.autologin.R
import top.steins.autologin.network.update.UpdateInfo

@Composable
fun UpdateDialog(update: UpdateInfo, onDownload: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_dialog_title, update.version)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.update_dialog_message, BuildConfig.VERSION_NAME))
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
            TextButton(onClick = onDownload) { Text(stringResource(R.string.update_download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
        }
    )
}
