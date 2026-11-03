package top.steins.autologin.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.steins.autologin.R
import top.steins.autologin.SeamlessUiState
import top.steins.autologin.domain.model.SEAMLESS_DEVICE_LIMIT
import top.steins.autologin.domain.model.SeamlessAction
import top.steins.autologin.domain.model.SeamlessStatus
import top.steins.autologin.domain.rule.canonicalMac
import top.steins.autologin.ui.component.DeviceListCardContent
import top.steins.autologin.ui.component.DeviceListEntry
import top.steins.autologin.ui.component.ElasticSwitch
import top.steins.autologin.ui.component.ElasticTextButton
import top.steins.autologin.ui.component.AppSheet
import top.steins.autologin.ui.component.NavigationIconButton
import top.steins.autologin.ui.component.RefreshIconButton
import top.steins.autologin.ui.component.SettingsOptionCard
import top.steins.autologin.ui.component.sheetScrollable
import top.steins.autologin.ui.theme.CardHorizontalPadding
import top.steins.autologin.ui.theme.AppControlShape
import top.steins.autologin.ui.theme.appShadow
import top.steins.autologin.ui.theme.AppInfoCardShape

@Composable
private fun seamlessStatusText(state: SeamlessUiState): String? = when {
    state.info == null && (state.isLoading || state.error.isBlank()) -> stringResource(R.string.seamless_loading)
    state.info?.status == SeamlessStatus.Enabled || state.info?.status == SeamlessStatus.Disabled -> null
    else -> stringResource(R.string.seamless_unknown)
}

@Composable
fun SeamlessLoginScreen(
    state: SeamlessUiState,
    onRefresh: () -> Unit,
    onAction: (SeamlessAction) -> Unit,
    onNavigateBack: () -> Unit,
    localIpAddress: String = ""
) {
    val info = state.info
    val list = info?.deviceList
    val switchLabel = stringResource(R.string.seamless_title)
    var showEditor by rememberSaveable(info?.username, info?.sessionGeneration) { mutableStateOf(false) }
    var macInput by rememberSaveable(info?.username, info?.sessionGeneration) { mutableStateOf("") }
    var invalidMac by rememberSaveable(info?.username, info?.sessionGeneration) { mutableStateOf(false) }
    var pendingAction by remember(info?.username, info?.sessionGeneration) { mutableStateOf<SeamlessAction?>(null) }
    val lowerCardsVisible = list != null
    // 当前会话已有列表时直接恢复卡片，首次加载仍播放入场动画。
    var showLowerCardsImmediately by remember { mutableStateOf(lowerCardsVisible) }
    var retainedInfo by remember { mutableStateOf(info) }
    var retainLowerCards by remember { mutableStateOf(lowerCardsVisible) }

    LaunchedEffect(lowerCardsVisible, info) {
        if (lowerCardsVisible) {
            retainedInfo = info
            retainLowerCards = true
        } else if (retainLowerCards) {
            showLowerCardsImmediately = false
            // 与主页一致：退出期间保留快照，完整播放动画后才清理卡片。
            withFrameNanos { }
            delay(CardAnimationDurationMillis.toLong())
            withFrameNanos { }
            retainLowerCards = false
            retainedInfo = null
        }
    }
    val lowerInfo = if (lowerCardsVisible) info else retainedInfo


    val listState = rememberLazyListState()
    AppSheet(
        title = stringResource(R.string.seamless_title),
        onDismissRequest = onNavigateBack,
        dismissEnabled = !state.isSubmitting,
        actions = {
            NavigationIconButton(
                onClick = { macInput = ""; invalidMac = false; showEditor = true },
                icon = R.drawable.plus,
                contentDescription = stringResource(R.string.seamless_add),
                modifier = Modifier.padding(end = 4.dp),
                enabled = state.canPrepareAction && list != null
            )
            RefreshIconButton(
                onClick = onRefresh,
                enabled = !state.isLoading && !state.isSubmitting,
                contentDescription = stringResource(R.string.seamless_refresh)
            )
        }
    ) { contentPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().sheetScrollable { listState.canScrollBackward },
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "seamless-status") {
                SettingsOptionCard(shape = AppInfoCardShape) {
                    Column(Modifier.padding(horizontal = CardHorizontalPadding, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.seamless_title), style = MaterialTheme.typography.titleMedium)
                                seamlessStatusText(state)?.let {
                                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            ElasticSwitch(
                                modifier = Modifier.semantics { contentDescription = switchLabel },
                                checked = info?.status == SeamlessStatus.Enabled,
                                enabled = state.canPrepareAction && info?.status != SeamlessStatus.Unknown,
                                onCheckedChange = { info?.let { pendingAction = SeamlessAction.Toggle(it.status) } }
                            )
                        }
                        if (state.isSubmitting) Text(stringResource(R.string.seamless_submitting),
                            style = MaterialTheme.typography.bodyMedium)
                        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
                        if (!info?.statusError.isNullOrBlank()) Text(info.statusError, color = MaterialTheme.colorScheme.error)
                        if (info?.status != SeamlessStatus.Disabled && !info?.devicesError.isNullOrBlank()) {
                            Text(info.devicesError, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if ((lowerCardsVisible || retainLowerCards) && lowerInfo?.deviceList != null) {
                item(key = "seamless-devices") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StaggeredCard(visible = lowerCardsVisible, initiallyVisible = showLowerCardsImmediately, index = 0) {
                            DeviceListCardContent(
                                // 倒序展示记录，让新设备在上、满额时被替换的最早记录在下。
                                devices = lowerInfo.deviceList.devices.asReversed().map { device ->
                                    DeviceListEntry(
                                        id = device.macAddress,
                                        macAddress = device.macAddress,
                                        ipAddressLabel = stringResource(
                                            R.string.seamless_last_ip,
                                            device.lastLoginIp.ifBlank { stringResource(R.string.value_placeholder) }
                                        ),
                                        isCurrentDevice = device.isOnline == true &&
                                            device.lastLoginIp.isNotBlank() &&
                                            device.lastLoginIp.trim() == localIpAddress.trim(),
                                        removeDeviceDescription = stringResource(R.string.seamless_unbind),
                                        statusText = stringResource(when (device.isOnline) {
                                            true -> R.string.seamless_online
                                            false -> R.string.seamless_offline
                                            null -> R.string.seamless_unknown
                                        }),
                                        isOnline = device.isOnline == true
                                    )
                                },
                                title = stringResource(R.string.seamless_count, lowerInfo.deviceList.total, SEAMLESS_DEVICE_LIMIT),
                                emptyText = stringResource(R.string.seamless_empty),
                                animationDurationMillis = CardAnimationDurationMillis,
                                removeDeviceIcon = R.drawable.link_2_off,
                                enabled = state.canPrepareAction && lowerCardsVisible,
                                onRemoveDevice = { pendingAction = SeamlessAction.Unbind(it.macAddress) }
                            )
                        }
                    }
                }
            }
            if (state.operationMessage.isNotBlank()) item {
                Text(state.operationMessage, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showEditor) AlertDialog(
        onDismissRequest = { showEditor = false },
        title = { Text(stringResource(R.string.seamless_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.seamless_limit_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = macInput, onValueChange = { macInput = it; invalidMac = false },
                    modifier = Modifier.appShadow(AppControlShape),
                    label = { Text(stringResource(R.string.seamless_mac_label)) }, singleLine = true,
                    isError = invalidMac, shape = AppControlShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        errorContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ))
                Text(stringResource(if (invalidMac) R.string.seamless_invalid_mac else R.string.seamless_mac_hint))
            }
        },
        confirmButton = {
            ElasticTextButton(enabled = state.canManage && list != null, onClick = {
                val mac = canonicalMac(macInput)
                if (mac == null) invalidMac = true
                else if (list != null && list.total >= SEAMLESS_DEVICE_LIMIT) {
                    pendingAction = SeamlessAction.Bind(mac, allowReplacement = true)
                } else {
                    showEditor = false
                    onAction(SeamlessAction.Bind(mac))
                }
            }) { Text(stringResource(R.string.seamless_bind)) }
        },
        dismissButton = { ElasticTextButton(onClick = { showEditor = false }) { Text(stringResource(R.string.action_cancel)) } }
    )

    pendingAction?.let { action ->
        val disabling = action is SeamlessAction.Toggle && action.expectedStatus == SeamlessStatus.Enabled
        val title = when (action) {
            is SeamlessAction.Toggle -> if (disabling) R.string.seamless_disable_title else R.string.seamless_enable_title
            is SeamlessAction.Bind -> R.string.seamless_replace_title
            is SeamlessAction.Unbind -> R.string.seamless_unbind_title
        }
        val message = when (action) {
            is SeamlessAction.Toggle -> stringResource(if (disabling) R.string.seamless_disable_message else R.string.seamless_enable_message)
            is SeamlessAction.Bind -> stringResource(R.string.seamless_replace_message, action.macAddress)
            is SeamlessAction.Unbind -> stringResource(R.string.seamless_unbind_message, action.macAddress)
        }
        AlertDialog(onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(title)) }, text = { Text(message) },
            confirmButton = { ElasticTextButton(enabled = state.canManage, onClick = {
                pendingAction = null; showEditor = false; onAction(action)
            }) { Text(stringResource(R.string.seamless_confirm)) } },
            dismissButton = { ElasticTextButton(onClick = { pendingAction = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}
