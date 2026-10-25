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
import top.steins.autologin.state.AccountStateHolder
import top.steins.autologin.state.CampusSessionCoordinator
import top.steins.autologin.state.HttpLogStateHolder
import top.steins.autologin.state.SeamlessFeature
import top.steins.autologin.state.SettingsStateHolder
import top.steins.autologin.state.UpdateState
import top.steins.autologin.state.UpdateStateHolder
import top.steins.autologin.state.WifiScanStateHolder
import top.steins.autologin.ui.component.CapsuleToastState
import top.steins.autologin.ui.component.UpdateDialog
import top.steins.autologin.ui.screen.AboutScreen
import top.steins.autologin.ui.screen.AccountScreen
import top.steins.autologin.ui.screen.HomeScreen
import top.steins.autologin.ui.screen.LogScreen
import top.steins.autologin.ui.screen.SeamlessLoginScreen
import top.steins.autologin.ui.screen.SettingsScreen
import top.steins.autologin.ui.screen.SoftwareSettingsScreen
import top.steins.autologin.ui.screen.WifiConfigScreen

/**
 * 各导航页面只订阅自己需要的状态，避免日志或凭据变化触发整个 NavHost 重组。
 */
@Composable
fun HomeRoute(
    stateHolder: CampusSessionCoordinator,
    toastState: CapsuleToastState,
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSeamless: () -> Unit
) {
    val uiState by stateHolder.uiState.collectAsStateWithLifecycle()
    val targetSsids by stateHolder.targetSsids.collectAsStateWithLifecycle()

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
        targetSsids = targetSsids,
        onRefreshAccountInfo = stateHolder::refreshAccountInfo,
        onCheckNetworkStatus = stateHolder::refreshStatus,
        onRetryAccountInfo = stateHolder::retryAccountInfo,
        onLogin = stateHolder::requestLogin,
        onLogoutDevice = stateHolder::logoutDevice,
        isLoggingOutDevice = uiState.isLoggingOutDevice,
        onShowToast = toastState::show,
        onNavigateToAccount = onNavigateToAccount,
        onNavigateToSettings = onNavigateToSettings,
        seamlessStatus = uiState.seamlessStatus,
        onNavigateToSeamless = onNavigateToSeamless
    )
}

@Composable
fun SeamlessLoginRoute(feature: SeamlessFeature, onNavigateBack: () -> Unit) {
    val state by feature.state.collectAsStateWithLifecycle()
    val localIpAddress by feature.localIpAddress.collectAsStateWithLifecycle()
    LaunchedEffect(feature) {
        feature.refresh()
    }
    SeamlessLoginScreen(
        state = state,
        onRefresh = feature::refreshSession,
        onAction = feature::perform,
        onNavigateBack = onNavigateBack,
        localIpAddress = localIpAddress
    )
}

@Composable
fun AccountRoute(
    stateHolder: AccountStateHolder,
    toastState: CapsuleToastState,
    onNavigateBack: () -> Unit
) {
    val selectedAccountUsername by stateHolder.selectedAccountUsername.collectAsStateWithLifecycle()
    val accounts by stateHolder.accounts.collectAsStateWithLifecycle()

    AccountScreen(
        selectedAccountUsername = selectedAccountUsername,
        accounts = accounts,
        onSelectAccount = stateHolder::selectAccount,
        onRemoveAccount = stateHolder::removeAccount,
        onSaveCredentials = stateHolder::saveCredentials,
        onShowToast = toastState::show,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun SettingsRoute(
    logs: HttpLogStateHolder,
    onNavigateBack: () -> Unit,
    onNavigateToLog: () -> Unit,
    onNavigateToWifiConfig: () -> Unit,
    onNavigateToSoftwareSettings: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val httpLogEnabled by logs.httpLogEnabled.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // 随设置页返回栈保存最后完成的显示状态，返回后再播放待完成的插入或移除动画。
    var hasShownHttpLogEntry by rememberSaveable { mutableStateOf(httpLogEnabled) }

    SettingsScreen(
        httpLogEnabled = httpLogEnabled,
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
    stateHolder: SettingsStateHolder,
    onNavigateBack: () -> Unit
) {
    val appearanceMode by stateHolder.appearanceMode.collectAsStateWithLifecycle()
    val autoLoginOnStartup by stateHolder.autoLoginOnStartup.collectAsStateWithLifecycle()

    SoftwareSettingsScreen(
        appearanceMode = appearanceMode,
        onAppearanceModeChange = stateHolder::saveAppearanceMode,
        autoLoginOnStartup = autoLoginOnStartup,
        onAutoLoginOnStartupChange = stateHolder::setAutoLoginOnStartup,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun AboutRoute(
    stateHolder: UpdateStateHolder,
    logs: HttpLogStateHolder,
    toastState: CapsuleToastState,
    onNavigateBack: () -> Unit
) {
    val httpLogEnabled by logs.httpLogEnabled.collectAsStateWithLifecycle()
    val updateState by stateHolder.state.collectAsStateWithLifecycle()
    var showUpdateDetails by rememberSaveable { mutableStateOf(false) }
    val availableUpdate = (updateState as? UpdateState.Available)?.update
    if (showUpdateDetails && availableUpdate != null) {
        UpdateDialog(
            update = availableUpdate,
            download = (updateState as UpdateState.Available).download,
            onDownload = stateHolder::downloadAvailable,
            onDismiss = {
                showUpdateDetails = false
                stateHolder.cancelAutomaticInstall()
            }
        )
    }

    AboutScreen(
        httpLogEnabled = httpLogEnabled,
        updateState = updateState,
        onVersionClick = logs::onAboutVersionClicked,
        onShowToast = toastState::show,
        onNavigateBack = onNavigateBack,
        onCheckForUpdates = { stateHolder.check(manual = true) },
        onDownloadUpdate = { showUpdateDetails = true }
    )
}

@Composable
fun LogRoute(stateHolder: HttpLogStateHolder, onNavigateBack: () -> Unit) {
    val httpLogs by stateHolder.httpLogs.collectAsStateWithLifecycle()

    LogScreen(
        entries = httpLogs,
        onClearLogs = stateHolder::clearHttpLogs,
        onDisableHttpLog = stateHolder::disableHttpLog,
        onNavigateBack = onNavigateBack
    )
}

@Composable
fun WifiConfigRoute(
    stateHolder: SettingsStateHolder,
    scan: WifiScanStateHolder,
    toastState: CapsuleToastState,
    onNavigateBack: () -> Unit
) {
    val targetSsids by stateHolder.targetSsids.collectAsStateWithLifecycle()

    WifiConfigScreen(
        targetSsids = targetSsids,
        onAddTargetWifi = stateHolder::addTargetWifi,
        onRemoveTargetWifi = stateHolder::removeTargetWifi,
        onShowToast = toastState::show,
        onNavigateBack = onNavigateBack,
        scanContent = { configuredSsids, onSelectWifi ->
            WifiScanRoute(scan, configuredSsids, onSelectWifi)
        }
    )
}
