package top.steins.autologin.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.network.WifiScanOutcome
import top.steins.autologin.network.hasWifiScanPermission
import top.steins.autologin.network.scanNearbyWifi
import top.steins.autologin.network.wifiScanPermissionsForRequest
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.appCardElevation

// ==================== WiFi 扫描结果 Sheet ====================

@Composable
private fun WifiScanResultRow(
    ssid: String,
    strength: Int,
    isConnected: Boolean,
    isAlreadyConfigured: Boolean,
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
        modifier = rowModifier.padding(horizontal = 16.dp, vertical = 12.dp),
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
    onSelectWifi: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanOutcome by remember { mutableStateOf<WifiScanOutcome?>(null) }
    var isScanning by remember { mutableStateOf(true) }

    fun runScan() {
        scope.launch {
            isScanning = true
            scanOutcome = scanNearbyWifi(context)
            isScanning = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        runScan()
    }

    fun refreshScan() {
        if (!hasWifiScanPermission(context)) {
            isScanning = false
            scanOutcome = WifiScanOutcome.PermissionDenied
            permissionLauncher.launch(wifiScanPermissionsForRequest())
        } else {
            runScan()
        }
    }

    LaunchedEffect(Unit) {
        refreshScan()
    }

    Column(
        // 从打开时就保留展开高度，让半屏锚点在扫描和结果切换时保持稳定。
        // 默认停在半屏，手动上拉时沿用 BottomSheet 的拖动和展开动画。
        modifier = Modifier.fillMaxWidth().fillMaxHeight()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalPadding)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.wifi_scan_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = ::refreshScan, enabled = !isScanning) {
                Text(stringResource(R.string.action_refresh))
            }
        }

        when {
            isScanning -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding)
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
                        .padding(horizontal = ScreenHorizontalPadding)
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
                        .padding(horizontal = ScreenHorizontalPadding)
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
                        .padding(horizontal = ScreenHorizontalPadding)
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
                        .padding(horizontal = ScreenHorizontalPadding)
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
                        .padding(horizontal = ScreenHorizontalPadding)
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = (scanOutcome as WifiScanOutcome.Failure).message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            scanOutcome is WifiScanOutcome.Success -> {
                val success = scanOutcome as WifiScanOutcome.Success
                if (!success.isFreshResult) {
                    Text(
                        text = stringResource(R.string.wifi_scan_stale),
                        modifier = Modifier.padding(
                            horizontal = ScreenHorizontalPadding,
                            vertical = 4.dp
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    // 底部安全留白随列表滚动，避免固定色块提前截断列表视口。
                    contentPadding = PaddingValues(
                        start = ScreenHorizontalPadding,
                        top = 8.dp,
                        end = ScreenHorizontalPadding,
                        bottom = 16.dp + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
                    )
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppCardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ),
                            elevation = appCardElevation()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                success.results.forEach { result ->
                                    WifiScanResultRow(
                                        ssid = result.ssid,
                                        strength = result.strength,
                                        isConnected = result.isConnected,
                                        isAlreadyConfigured = result.ssid in configuredSsids,
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
