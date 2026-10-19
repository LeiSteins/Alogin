package top.steins.autologin.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.ui.component.ScaleFadeBox
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.TopBarHeight

internal const val CardAnimationDurationMillis = 250

private enum class HomePresentationStage {
    Online,
    RemovingCurrentDevice,
    HidingOnline,
    Offline,
    HidingNetwork
}

private data class HomeOnlineContent(
    val overview: AccountOverview?,
    val errorMessage: String,
    val isDeviceListAvailable: Boolean,
    val wifiName: String,
    val ipAddress: String
)

private suspend fun awaitCardAnimation() {
    // 等待重组启动动画，并在末帧之后切换展示阶段。
    withFrameNanos { }
    delay(CardAnimationDurationMillis.toLong())
    withFrameNanos { }
}

internal enum class AccountInfoContent {
    Empty,
    Error,
    Overview,
    OverviewWithError
}
internal data class AccountInfoContentState(
    val content: AccountInfoContent,
    val overview: AccountOverview?,
    val errorMessage: String
)

@Composable
fun HomeScreen(
    wifiName: String,
    ipAddress: String,
    isOnline: Boolean,
    isLoggingIn: Boolean,
    accountOverview: AccountOverview?,
    isAccountInfoLoading: Boolean,
    accountInfoError: String,
    isDeviceListAvailable: Boolean,
    canLogoutDevices: Boolean,
    networkStatusError: String,
    targetWifis: List<String>,
    onRefreshAccountInfo: () -> Unit,
    onCheckNetworkStatus: () -> Unit,
    onRetryAccountInfo: () -> Unit,
    onLogin: () -> Unit,
    onLogoutDevice: suspend (String, String, String) -> DeviceLogoutResult,
    onRefreshAfterDeviceLogout: (Int) -> Unit,
    onRefreshAfterIndeterminateDeviceLogout: () -> Unit,
    onShowToast: (String) -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val resources = LocalResources.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var isDeletingDevice by remember { mutableStateOf(false) }
    val currentOnlineContent = HomeOnlineContent(
        accountOverview, accountInfoError, isDeviceListAvailable, wifiName, ipAddress
    )
    var lastOnlineContent by remember { mutableStateOf(currentOnlineContent) }
    var presentationStage by remember {
        mutableStateOf(if (isOnline) HomePresentationStage.Online else HomePresentationStage.Offline)
    }
    var hideCurrentDevice by remember { mutableStateOf(false) }
    // 此状态随 Home 的导航返回栈条目保存，返回主页时无需重播装饰性入场动画。
    var hasPresentedHome by rememberSaveable { mutableStateOf(false) }
    val hadAccountContentOnEntry = remember {
        accountOverview != null || accountInfoError.isNotBlank()
    }
    var showOnlineCardsImmediately by remember { mutableStateOf(hasPresentedHome) }
    var showNetworkCardImmediately by remember { mutableStateOf(hasPresentedHome) }
    var previousOnlineState by remember { mutableStateOf(isOnline) }

    LaunchedEffect(Unit) {
        hasPresentedHome = true
    }

    LaunchedEffect(isOnline, currentOnlineContent) {
        if (isOnline) {
            lastOnlineContent = currentOnlineContent
        }
    }

    // 离线确认会清空业务状态；保留完整在线快照，直到行删除和卡片退场全部完成。
    val contentForCards = if (isOnline) currentOnlineContent else lastOnlineContent
    val overviewForCards = contentForCards.overview
    val isTargetWifi = targetWifis.contains(wifiName)
    val renderNetworkCard = presentationStage == HomePresentationStage.Offline ||
            presentationStage == HomePresentationStage.HidingNetwork
    val onlineContentVisible = presentationStage == HomePresentationStage.Online ||
            presentationStage == HomePresentationStage.RemovingCurrentDevice ||
            presentationStage == HomePresentationStage.HidingNetwork
    val canRenderOnlineCards = !renderNetworkCard &&
            (overviewForCards != null || contentForCards.errorMessage.isNotBlank())
    val isGoingOffline = !isOnline && presentationStage != HomePresentationStage.Offline
    val devicesForCards = overviewForCards
        ?.devices
        .orEmpty()
        .filterNot { device -> hideCurrentDevice && device.isCurrentDevice(contentForCards.ipAddress) }
        .sortedByDescending { device -> device.isCurrentDevice(contentForCards.ipAddress) }

    LaunchedEffect(isOnline) {
        val wasOnline = previousOnlineState
        previousOnlineState = isOnline
        if (isOnline) {
            if (!wasOnline) {
                showOnlineCardsImmediately = false
            }
            hideCurrentDevice = false
            if (renderNetworkCard) {
                presentationStage = HomePresentationStage.HidingNetwork
                awaitCardAnimation()
            }
            presentationStage = HomePresentationStage.Online
        } else {
            if (wasOnline) {
                showNetworkCardImmediately = false
                val hasCurrentDevice = contentForCards.isDeviceListAvailable &&
                        overviewForCards?.devices.orEmpty().any { device ->
                            device.isCurrentDevice(contentForCards.ipAddress)
                        }
                if (presentationStage == HomePresentationStage.Online && hasCurrentDevice) {
                    hideCurrentDevice = true
                    presentationStage = HomePresentationStage.RemovingCurrentDevice
                    awaitCardAnimation()
                }
                presentationStage = HomePresentationStage.HidingOnline
                awaitCardAnimation()
            }
            presentationStage = HomePresentationStage.Offline
        }
    }

    fun performPrimaryAction() {
        when {
            isOnline -> {
                onRefreshAccountInfo()
                scope.launch {
                    onShowToast(resources.getString(R.string.home_refreshing_account))
                }
            }

            isTargetWifi -> {
                onLogin()
            }

            else -> {
                scope.launch {
                    onShowToast(resources.getString(R.string.home_refreshing))
                    onCheckNetworkStatus()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f)
                                )
                            )
                        )
                        .statusBarsPadding()
                        .height(TopBarHeight)
                        .padding(start = ScreenHorizontalPadding, end = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp, end = 4.dp)
                    ) {
                        ScaleFadeBox(
                            visible = onlineContentVisible,
                            initiallyVisible = showOnlineCardsImmediately,
                            durationMillis = CardAnimationDurationMillis,
                            modifier = Modifier
                                .fillMaxWidth()
                        ) {
                            NetworkInfoSummary(
                                wifiName = contentForCards.wifiName,
                                ipAddress = contentForCards.ipAddress
                            )
                        }
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.settings),
                            contentDescription = stringResource(R.string.nav_settings),
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onNavigateToAccount,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.manage_accounts),
                            contentDescription = stringResource(R.string.account_title),
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            floatingActionButton = {
                FloatingActionBox(
                    label = when {
                        isOnline -> stringResource(R.string.home_primary_refresh)
                        isTargetWifi -> stringResource(R.string.home_primary_login)
                        else -> stringResource(R.string.home_primary_refresh)
                    },
                    isLoading = isLoggingIn || isAccountInfoLoading,
                    enabled = !isLoggingIn && !isAccountInfoLoading && !isDeletingDevice &&
                            !isGoingOffline,
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        performPrimaryAction()
                    },
                    modifier = Modifier
                        .width(140.dp)
                        .padding(bottom = 8.dp)
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ScreenHorizontalPadding,
                    end = ScreenHorizontalPadding,
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (renderNetworkCard) {
                    item(key = "network-info") {
                        StaggeredCard(
                            visible = !isOnline,
                            initiallyVisible = showNetworkCardImmediately,
                            index = 0
                        ) {
                            NetworkInfoCard(
                                wifiName = wifiName,
                                ipAddress = ipAddress,
                                errorMessage = networkStatusError
                            )
                        }
                    }
                }

                if (canRenderOnlineCards) {
                    item(key = "account-info") {
                        StaggeredCard(
                            visible = onlineContentVisible,
                            initiallyVisible = showOnlineCardsImmediately && hadAccountContentOnEntry,
                            index = 0
                        ) {
                            AccountInfoCard(
                                overview = overviewForCards,
                                errorMessage = contentForCards.errorMessage,
                                onRetry = onRetryAccountInfo
                            )
                        }
                    }

                    if (overviewForCards != null && contentForCards.isDeviceListAvailable) {
                        item(key = "device-list") {
                            StaggeredCard(
                                visible = onlineContentVisible,
                                initiallyVisible = showOnlineCardsImmediately && hadAccountContentOnEntry,
                                index = 1
                            ) {
                                DeviceListCard(
                                    devices = devicesForCards,
                                    localIpAddress = contentForCards.ipAddress,
                                    enabled = isOnline && !isDeletingDevice && canLogoutDevices,
                                    showEmptyState = isOnline,
                                    onDelete = { device ->
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                        isDeletingDevice = true
                                        scope.launch {
                                            try {
                                                when (
                                                    val result = onLogoutDevice(
                                                        device.sessionId,
                                                        device.ipAddress,
                                                        device.macAddress
                                                    )
                                                ) {
                                                    DeviceLogoutResult.Success -> {
                                                        onShowToast(
                                                            resources.getString(
                                                                R.string.delete_device_success,
                                                                device.macAddress
                                                            )
                                                        )
                                                        onRefreshAfterDeviceLogout(1)
                                                    }

                                                    is DeviceLogoutResult.Failure -> {
                                                        onShowToast(result.message)
                                                    }

                                                    is DeviceLogoutResult.Indeterminate -> {
                                                        onShowToast(result.message)
                                                        onRefreshAfterIndeterminateDeviceLogout()
                                                    }
                                                }
                                            } finally {
                                                isDeletingDevice = false
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

    }
}
