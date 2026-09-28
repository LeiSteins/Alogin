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
import androidx.compose.foundation.lazy.itemsIndexed
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
import top.steins.autologin.network.LoginResult
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.TopBarHeight

internal const val CardAnimationDurationMillis = 250
internal const val CardStaggerDelayMillis = 60

internal enum class AccountInfoContent {
    Empty,
    Loading,
    Error,
    LoadingWithError,
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
    onLogin: suspend () -> LoginResult,
    onConfirmLogin: () -> Unit,
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

    var isLoggingIn by remember { mutableStateOf(false) }
    var isDeletingDevice by remember { mutableStateOf(false) }
    var lastAccountOverview by remember { mutableStateOf<AccountOverview?>(null) }
    var renderOnlineCards by remember { mutableStateOf(isOnline) }
    // 此状态随 Home 的导航返回栈条目保存，返回主页时无需重播装饰性入场动画。
    var hasPresentedHome by rememberSaveable { mutableStateOf(false) }
    var showOnlineCardsImmediately by remember { mutableStateOf(hasPresentedHome) }
    var previousOnlineState by remember { mutableStateOf(isOnline) }

    LaunchedEffect(Unit) {
        hasPresentedHome = true
    }

    LaunchedEffect(accountOverview) {
        if (accountOverview != null) {
            lastAccountOverview = accountOverview
        }
    }

    val isTargetWifi = targetWifis.contains(wifiName)
    // 网络离线后 ViewModel 会清空账号信息；保留上一份数据直到退出动画播放完成。
    val overviewForCards = accountOverview ?: if (!isOnline) lastAccountOverview else null
    val devicesForCards = overviewForCards
        ?.devices
        .orEmpty()
        .sortedByDescending { device -> device.isCurrentDevice(ipAddress) }
    val onlineCardCount = 1 + when {
        overviewForCards == null -> 0
        !isDeviceListAvailable -> 0
        devicesForCards.isEmpty() -> 1
        else -> devicesForCards.size
    }

    LaunchedEffect(isOnline) {
        val wasOnline = previousOnlineState
        previousOnlineState = isOnline
        if (isOnline) {
            if (!wasOnline) {
                showOnlineCardsImmediately = false
            }
            renderOnlineCards = true
        } else if (renderOnlineCards) {
            delay(
                CardAnimationDurationMillis +
                        (onlineCardCount - 1) * CardStaggerDelayMillis.toLong()
            )
            renderOnlineCards = false
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
                isLoggingIn = true
                scope.launch {
                    val result = onLogin()
                    isLoggingIn = false
                    when (result) {
                        is LoginResult.Success -> {
                            onShowToast(
                                resources.getString(R.string.home_login_success_fetching)
                            )
                            onConfirmLogin()
                        }

                        is LoginResult.Failure -> onShowToast(result.message)
                        is LoginResult.NetworkError -> onShowToast(result.message)
                    }
                }
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
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.settings),
                            contentDescription = stringResource(R.string.nav_settings),
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onNavigateToAccount,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.manage_accounts),
                            contentDescription = stringResource(R.string.account_title),
                            modifier = Modifier.size(26.dp),
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
                    enabled = !isLoggingIn && !isAccountInfoLoading && !isDeletingDevice,
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    NetworkInfoCard(
                        wifiName = wifiName,
                        ipAddress = ipAddress,
                        errorMessage = networkStatusError
                    )
                }

                if (renderOnlineCards) {
                    item(key = "account-info") {
                        StaggeredCard(
                            visible = isOnline,
                            initiallyVisible = showOnlineCardsImmediately,
                            index = 0,
                            count = onlineCardCount
                        ) {
                            AccountInfoCard(
                                overview = overviewForCards,
                                isLoading = isAccountInfoLoading,
                                errorMessage = accountInfoError,
                                onRetry = onRetryAccountInfo
                            )
                        }
                    }

                    if (overviewForCards != null && isDeviceListAvailable) {
                        if (devicesForCards.isEmpty()) {
                            item(key = "empty-device") {
                                StaggeredCard(
                                    visible = isOnline,
                                    initiallyVisible = showOnlineCardsImmediately,
                                    index = 1,
                                    count = onlineCardCount
                                ) {
                                    EmptyDeviceCard()
                                }
                            }
                        } else {
                            itemsIndexed(
                                items = devicesForCards,
                                key = { _, device -> device.sessionId }
                            ) { deviceIndex, device ->
                                StaggeredCard(
                                    visible = isOnline,
                                    initiallyVisible = showOnlineCardsImmediately,
                                    index = deviceIndex + 1,
                                    count = onlineCardCount
                                ) {
                                    DeviceCard(
                                        device = device,
                                        isCurrentDevice = device.isCurrentDevice(ipAddress),
                                        enabled = !isDeletingDevice && canLogoutDevices,
                                        onDelete = {
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
}
