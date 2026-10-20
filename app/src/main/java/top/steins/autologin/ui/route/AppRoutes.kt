package top.steins.autologin.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import top.steins.autologin.AppViewModel
import top.steins.autologin.network.update.UpdateState
import top.steins.autologin.ui.component.CapsuleToastState
import top.steins.autologin.ui.component.UpdateDialog
import top.steins.autologin.ui.screen.AboutScreen
import top.steins.autologin.ui.screen.AccountScreen
import top.steins.autologin.ui.screen.HomeScreen
import top.steins.autologin.ui.screen.LogScreen
import top.steins.autologin.ui.screen.SoftwareSettingsScreen
import top.steins.autologin.ui.screen.SettingsScreen
import top.steins.autologin.ui.screen.WifiConfigScreen
import top.steins.autologin.ui.screen.SeamlessLoginScreen

/**
 * 各导航页面只订阅自己需要的状态，避免日志或凭据变化触发整个 NavHost 重组。
 */
@Composable
fun HomeRoute(
    viewModel: AppViewModel,
    toastState: CapsuleToastState,
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSeamless: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val targetWifis by viewModel.targetWifis.collectAsStateWithLifecycle()

    HomeScreen(
        wifiName = uiState.wifiName,
        ipAddress = uiState.ipAddress,
        isOnline = uiState.isOnline,
        isLoggingIn = uiState.isLoggingIn,
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
        onLogin = viewModel::requestLogin,
        onLogoutDevice = viewModel::logoutDevice,
        onRefreshAfterDeviceLogout = viewModel::refreshAfterDeviceLogout,
        onRefreshAfterIndeterminateDeviceLogout =
            viewModel::refreshAfterIndeterminateDeviceLogout,
        onShowToast = toastState::show,
        onNavigateToAccount = onNavigateToAccount,
        onNavigateToSettings = onNavigateToSettings,
        seamlessStatus = uiState.seamlessStatus,
        onNavigateToSeamless = onNavigateToSeamless
    )
}

@Composable
fun SeamlessLoginRoute(viewModel: AppViewModel, onNavigateBack: () -> Unit) {
    val state by viewModel.seamlessState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refreshSeamlessInfo()
    }
    SeamlessLoginScreen(state,
        viewModel::refreshAccountInfo, viewModel::performSeamlessAction, onNavigateBack,
        localIpAddress = uiState.ipAddress)
}

@Composable
fun AccountRoute(
    viewModel: AppViewModel,
    toastState: CapsuleToastState,
    onNavigateBack: () -> Unit
) {
    val username by viewModel.username.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    AccountScreen(
        username = username,
        accounts = accounts,
        onSelectAccount = viewModel::selectAccount,
        onRemoveAccount = viewModel::removeAccount,
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
    onNavigateToSoftwareSettings: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val targetWifis by viewModel.targetWifis.collectAsStateWithLifecycle()
    val httpLogEnabled by viewModel.httpLogEnabled.collectAsStateWithLifecycle()
    val httpLogs by viewModel.httpLogs.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // 随设置页返回栈保存最后完成的显示状态，返回后再播放待完成的插入或移除动画。
    var hasShownHttpLogEntry by rememberSaveable { mutableStateOf(httpLogEnabled) }

    SettingsScreen(
        targetWifiCount = targetWifis.size,
        httpLogEnabled = httpLogEnabled,
        logEntryCount = httpLogs.size,
        onNavigateBack = onNavigateBack,
        onNavigateToLog = onNavigateToLog,
        onNavigateToWifiConfig = onNavigateToWifiConfig,
        onNavigateToSoftwareSettings = onNavigateToSoftwareSettings,
        onNavigateToAbout = onNavigateToAbout,
        animateHttpLogEntry = httpLogEnabled != hasShownHttpLogEntry,
        isPageResumed = lifecycleState == Lifecycle.State.RESUMED,
        onHttpLogEntryAnimationFinished = { hasShownHttpLogEntry = httpLogEnabled }
    )
}

@Composable
fun SoftwareSettingsRoute(
    viewModel: AppViewModel,
    onNavigateBack: () -> Unit
) {
    val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
    val autoLoginOnStartup by viewModel.autoLoginOnStartup.collectAsStateWithLifecycle()

    SoftwareSettingsScreen(
        appearanceMode = appearanceMode,
        onAppearanceModeChange = viewModel::saveAppearanceMode,
        autoLoginOnStartup = autoLoginOnStartup,
        onAutoLoginOnStartupChange = viewModel::setAutoLoginOnStartup,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun AboutRoute(
    viewModel: AppViewModel,
    toastState: CapsuleToastState,
    onNavigateBack: () -> Unit
) {
    val httpLogEnabled by viewModel.httpLogEnabled.collectAsStateWithLifecycle()
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

    AboutScreen(
        httpLogEnabled = httpLogEnabled,
        updateState = updateState,
        onVersionClick = viewModel::onAboutVersionClicked,
        onShowToast = toastState::show,
        onNavigateBack = onNavigateBack,
        onCheckForUpdates = { viewModel.checkForUpdates(manual = true) },
        onDownloadUpdate = { showUpdateDetails = true }
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
