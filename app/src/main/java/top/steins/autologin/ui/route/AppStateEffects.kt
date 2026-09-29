package top.steins.autologin.ui.route

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.steins.autologin.AppViewModel
import top.steins.autologin.R
import top.steins.autologin.network.update.UpdateState
import top.steins.autologin.ui.component.CapsuleToastState
import top.steins.autologin.ui.component.UpdateDialog

/** 将权限副作用限制在独立重组作用域中。 */
@Composable
fun LocationPermissionEffect(viewModel: AppViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onLocationPermissionChanged()
    }

    LaunchedEffect(uiState.hasLocationPermission) {
        if (!uiState.hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }
}

/** 收集一次性消息，不让消息流影响导航树的重组范围。 */
@Composable
fun UpdateMessageEffect(viewModel: AppViewModel, toastState: CapsuleToastState) {
    LaunchedEffect(viewModel, toastState) {
        viewModel.updateMessages.collect(toastState::show)
    }
}

/** 只在弹窗区域订阅全局状态。 */
@Composable
fun AppDialogs(
    viewModel: AppViewModel,
    onNavigateToAccount: () -> Unit
) {
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val credentialResetPending by viewModel.credentialResetPending.collectAsStateWithLifecycle()
    var dismissedUpdateVersion by rememberSaveable { mutableStateOf<String?>(null) }

    val availableUpdate = (updateState as? UpdateState.Available)?.update
    if (availableUpdate != null && dismissedUpdateVersion != availableUpdate.version) {
        UpdateDialog(
            update = availableUpdate,
            download = (updateState as UpdateState.Available).download,
            onDownload = viewModel::downloadAvailableUpdate,
            onDismiss = {
                dismissedUpdateVersion = availableUpdate.version
                viewModel.cancelAutomaticUpdateInstall()
            }
        )
    }

    if (credentialResetPending) {
        AlertDialog(
            onDismissRequest = viewModel::acknowledgeCredentialReset,
            title = {
                Text(stringResource(R.string.credential_reset_title))
            },
            text = {
                Text(stringResource(R.string.credential_reset_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.acknowledgeCredentialReset()
                        onNavigateToAccount()
                    }
                ) {
                    Text(stringResource(R.string.credential_reset_fill_now))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::acknowledgeCredentialReset) {
                    Text(stringResource(R.string.credential_reset_later))
                }
            }
        )
    }
}
