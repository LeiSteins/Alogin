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
import top.steins.autologin.R
import top.steins.autologin.state.AccountStateHolder
import top.steins.autologin.state.CampusSessionCoordinator
import top.steins.autologin.state.UpdateState
import top.steins.autologin.state.UpdateStateHolder
import top.steins.autologin.ui.component.CapsuleToastState
import top.steins.autologin.ui.component.UpdateDialog

/** 将权限副作用限制在独立重组作用域中。 */
@Composable
fun LocationPermissionEffect(stateHolder: CampusSessionCoordinator) {
    val uiState by stateHolder.uiState.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        stateHolder.onLocationPermissionChanged()
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
fun UpdateMessageEffect(stateHolder: UpdateStateHolder, toastState: CapsuleToastState) {
    LaunchedEffect(stateHolder, toastState) {
        stateHolder.messages.collect(toastState::show)
    }
}

/** 登录结果随 ViewModel 保留，离开主页或 Activity 重建不会中断认证和确认刷新。 */
@Composable
fun LoginMessageEffect(stateHolder: CampusSessionCoordinator, toastState: CapsuleToastState) {
    val uiState by stateHolder.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.loginMessage) {
        uiState.loginMessage?.let {
            toastState.show(it)
            stateHolder.consumeLoginMessage()
        }
    }
}

/** 下线结果由应用壳消费，离开主页后仍可显示操作反馈。 */
@Composable
fun DeviceLogoutMessageEffect(stateHolder: CampusSessionCoordinator, toastState: CapsuleToastState) {
    val uiState by stateHolder.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.deviceLogoutMessage) {
        uiState.deviceLogoutMessage?.let {
            toastState.show(it)
            stateHolder.consumeDeviceLogoutMessage()
        }
    }
}

/** 只在弹窗区域订阅全局状态。 */
@Composable
fun AppDialogs(
    updates: UpdateStateHolder,
    accounts: AccountStateHolder,
    onNavigateToAccount: () -> Unit
) {
    val updateState by updates.state.collectAsStateWithLifecycle()
    val credentialResetPending by accounts.credentialResetPending.collectAsStateWithLifecycle()
    var dismissedUpdateVersion by rememberSaveable { mutableStateOf<String?>(null) }

    val availableUpdate = (updateState as? UpdateState.Available)?.update
    if (availableUpdate != null && dismissedUpdateVersion != availableUpdate.version) {
        UpdateDialog(
            update = availableUpdate,
            download = (updateState as UpdateState.Available).download,
            onDownload = updates::downloadAvailable,
            onDismiss = {
                dismissedUpdateVersion = availableUpdate.version
                updates.cancelAutomaticInstall()
            }
        )
    }

    if (credentialResetPending) {
        AlertDialog(
            onDismissRequest = accounts::acknowledgeCredentialReset,
            title = {
                Text(stringResource(R.string.credential_reset_title))
            },
            text = {
                Text(stringResource(R.string.credential_reset_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        accounts.acknowledgeCredentialReset()
                        onNavigateToAccount()
                    }
                ) {
                    Text(stringResource(R.string.credential_reset_fill_now))
                }
            },
            dismissButton = {
                TextButton(onClick = accounts::acknowledgeCredentialReset) {
                    Text(stringResource(R.string.credential_reset_later))
                }
            }
        )
    }
}
