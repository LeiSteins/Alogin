package top.steins.autologin.ui.route

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.steins.autologin.network.hasWifiScanPermission
import top.steins.autologin.network.wifiScanPermissionsForRequest
import top.steins.autologin.state.WifiScanStateHolder
import top.steins.autologin.ui.screen.WifiScanSheetContent

/** 权限交互属于 Route，扫描任务与展示状态由可测试的状态持有者管理。 */
@Composable
internal fun WifiScanRoute(
    stateHolder: WifiScanStateHolder,
    configuredSsids: Set<String>,
    onSelectWifi: (String) -> Unit
) {
    val context = LocalContext.current
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        stateHolder.refresh()
    }

    fun refresh() {
        if (hasWifiScanPermission(context)) {
            stateHolder.refresh()
        } else {
            stateHolder.permissionDenied()
            permissionLauncher.launch(wifiScanPermissionsForRequest())
        }
    }

    LaunchedEffect(stateHolder) {
        refresh()
    }
    DisposableEffect(stateHolder) {
        onDispose { stateHolder.cancel() }
    }

    WifiScanSheetContent(
        configuredSsids = configuredSsids,
        isScanning = state.isScanning,
        scanOutcome = state.outcome,
        onRefresh = ::refresh,
        onSelectWifi = onSelectWifi
    )
}
