package top.steins.autologin.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.steins.autologin.AppViewModel
import top.steins.autologin.network.update.UpdateState
import top.steins.autologin.ui.component.CapsuleToastState
import top.steins.autologin.ui.component.UpdateDialog
import top.steins.autologin.ui.screen.AboutScreen
import top.steins.autologin.ui.screen.AccountScreen
import top.steins.autologin.ui.screen.HomeScreen
import top.steins.autologin.ui.screen.LogScreen
import top.steins.autologin.ui.screen.SettingsScreen
import top.steins.autologin.ui.screen.WifiConfigScreen

/**
 * 各导航页面只订阅自己需要的状态，避免日志或凭据变化触发整个 NavHost 重组。
 */
@Composable
fun HomeRoute(
    viewModel: AppViewModel,
    toastState: CapsuleToastState,
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val targetWifis by viewModel.targetWifis.collectAsStateWithLifecycle()

    HomeScreen(
        wifiName = uiState.wifiName,
        ipAddress = uiState.ipAddress,
        isOnline = uiState.isOnline,
        accountOverview = uiState.accountOverview,
        isAccountInfoLoading = uiState.isAccountInfoLoading,
        accountInfoError = uiState.accountInfoError,
        isDeviceListAvailable = uiState.isDeviceListAvailable,
        canLogoutDevices = uiState.canLogoutDevices,
        networkStatusError = uiState.networkStatusError,
        targetWifis = targetWifis,
        onRefreshAccountInfo = viewModel::refreshAccountInfo,
        onCheckNetworkStatus = viewModel::refreshStatus,
        onRetryAccountInfo = viewModel::retryAccountInfo,
        onLogin = viewModel::login,
        onConfirmLogin = viewModel::confirmLogin,
        onLogoutDevice = viewModel::logoutDevice,
        onRefreshAfterDeviceLogout = viewModel::refreshAfterDeviceLogout,
        onRefreshAfterIndeterminateDeviceLogout =
            viewModel::refreshAfterIndeterminateDeviceLogout,
        onShowToast = toastState::show,
        onNavigateToAccount = onNavigateToAccount,
        onNavigateToSettings = onNavigateToSettings
    )
}

@Composable
fun AccountRoute(
    viewModel: AppViewModel,
    toastState: CapsuleToastState,
    onNavigateBack: () -> Unit
) {
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()

    AccountScreen(
        username = username,
        password = password,
        onSaveCredentials = viewModel::saveCredentials,
        onShowToast = toastState::show,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun SettingsRoute(
    viewModel: AppViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToLog: () -> Unit,
    onNavigateToWifiConfig: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val targetWifis by viewModel.targetWifis.collectAsStateWithLifecycle()
    val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
    val httpLogEnabled by viewModel.httpLogEnabled.collectAsStateWithLifecycle()
    val httpLogs by viewModel.httpLogs.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    var showUpdateDetails by rememberSaveable { mutableStateOf(false) }
    val availableUpdate = (updateState as? UpdateState.Available)?.update
    if (showUpdateDetails && availableUpdate != null) {
        UpdateDialog(
            update = availableUpdate,
            download = (updateState as UpdateState.Available).download,
            onDownload = viewModel::downloadAvailableUpdate,
            onDismiss = {
                showUpdateDetails = false
                viewModel.cancelAutomaticUpdateInstall()
            }
        )
    }

    SettingsScreen(
        targetWifiCount = targetWifis.size,
        appearanceMode = appearanceMode,
        onAppearanceModeChange = viewModel::saveAppearanceMode,
        httpLogEnabled = httpLogEnabled,
        logEntryCount = httpLogs.size,
        updateState = updateState,
        onNavigateBack = onNavigateBack,
        onNavigateToLog = onNavigateToLog,
        onNavigateToWifiConfig = onNavigateToWifiConfig,
        onNavigateToAbout = onNavigateToAbout,
        onCheckForUpdates = { viewModel.checkForUpdates(manual = true) },
        onDownloadUpdate = { showUpdateDetails = true }
    )
}

@Composable
fun AboutRoute(
    viewModel: AppViewModel,
    toastState: CapsuleToastState,
    onNavigateBack: () -> Unit
) {
    val httpLogEnabled by viewModel.httpLogEnabled.collectAsStateWithLifecycle()

    AboutScreen(
        httpLogEnabled = httpLogEnabled,
        onVersionClick = viewModel::onAboutVersionClicked,
        onShowToast = toastState::show,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun LogRoute(viewModel: AppViewModel, onNavigateBack: () -> Unit) {
    val httpLogs by viewModel.httpLogs.collectAsStateWithLifecycle()

    LogScreen(
        entries = httpLogs,
        onClearLogs = viewModel::clearHttpLogs,
        onDisableHttpLog = viewModel::disableHttpLog,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun WifiConfigRoute(
    viewModel: AppViewModel,
    toastState: CapsuleToastState,
    onNavigateBack: () -> Unit
) {
    val targetWifis by viewModel.targetWifis.collectAsStateWithLifecycle()

    WifiConfigScreen(
        targetWifis = targetWifis,
        onAddTargetWifi = viewModel::addTargetWifi,
        onRemoveTargetWifi = viewModel::removeTargetWifi,
        onShowToast = toastState::show,
        onNavigateBack = onNavigateBack
    )
}
