package top.steins.autologin.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import top.steins.autologin.ui.component.listRowSeparator
import top.steins.autologin.domain.model.WifiScanOutcome
import top.steins.autologin.ui.component.ElasticTextButton
import top.steins.autologin.ui.component.SheetActions
import top.steins.autologin.ui.component.sheetScrollable
import top.steins.autologin.ui.theme.AppListCardShape
import top.steins.autologin.ui.theme.ListRowHorizontalPadding
import top.steins.autologin.ui.theme.ListRowMinHeight
import top.steins.autologin.ui.theme.ListRowVerticalPadding
import top.steins.autologin.ui.theme.appCardElevation

// ==================== WiFi 扫描结果 Sheet ====================

@Composable
private fun WifiScanResultRow(
    ssid: String,
    strength: Int,
    isConnected: Boolean,
    isAlreadyConfigured: Boolean,
    showSeparator: Boolean,
    onClick: () -> Unit
) {
    val rowModifier = if (isAlreadyConfigured) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    }

    Row(
        modifier = rowModifier.heightIn(min = ListRowMinHeight)
            .listRowSeparator(visible = showSeparator)
            .padding(horizontal = ListRowHorizontalPadding, vertical = ListRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val contentColor = if (isAlreadyConfigured) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }

                Text(
                    text = ssid,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isConnected) FontWeight.Bold else FontWeight.Normal,
                    color = contentColor
                )
                if (isConnected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.wifi_connected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = signalStrengthLabel(strength),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isAlreadyConfigured) {
            Text(
                text = stringResource(R.string.wifi_configured),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun WifiScanSheetContent(
    configuredSsids: Set<String>,
    isScanning: Boolean,
    scanOutcome: WifiScanOutcome?,
    onRefresh: () -> Unit,
    onSelectWifi: (String) -> Unit,
    contentPadding: PaddingValues
) {
    val listState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    SheetActions {
        ElasticTextButton(onClick = onRefresh, enabled = !isScanning) {
            Text(stringResource(R.string.action_refresh))
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight()
    ) {
        when {
            isScanning -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.wifi_scanning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            scanOutcome is WifiScanOutcome.PermissionDenied -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.wifi_permission_denied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            scanOutcome is WifiScanOutcome.LocationDisabled -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.wifi_location_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            scanOutcome is WifiScanOutcome.WifiDisabled -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.wifi_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            scanOutcome is WifiScanOutcome.NoResults -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.wifi_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            scanOutcome is WifiScanOutcome.Failure -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = scanOutcome.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            scanOutcome is WifiScanOutcome.Success -> {
                val success = scanOutcome
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .sheetScrollable { listState.canScrollBackward },
                    // 底部安全留白随列表滚动，避免固定色块提前截断列表视口。
                    contentPadding = PaddingValues(
                        start = contentPadding.calculateStartPadding(layoutDirection),
                        top = contentPadding.calculateTopPadding(),
                        end = contentPadding.calculateEndPadding(layoutDirection),
                        bottom = contentPadding.calculateBottomPadding()
                    )
                ) {
                    if (!success.isFreshResult) {
                        item {
                            Text(stringResource(R.string.wifi_scan_stale),
                                modifier = Modifier.padding(vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppListCardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            elevation = appCardElevation()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                success.results.forEachIndexed { index, result ->
                                    WifiScanResultRow(
                                        ssid = result.ssid,
                                        strength = result.strength,
                                        isConnected = result.isConnected,
                                        isAlreadyConfigured = result.ssid in configuredSsids,
                                        showSeparator = index != success.results.lastIndex,
                                        onClick = { onSelectWifi(result.ssid) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
    }
}



@Composable
private fun signalStrengthLabel(strength: Int): String = stringResource(
    when {
        strength >= -50 -> R.string.signal_excellent
        strength >= -60 -> R.string.signal_strong
        strength >= -70 -> R.string.signal_medium
        strength >= -80 -> R.string.signal_weak
        else -> R.string.signal_very_weak
    }
)
