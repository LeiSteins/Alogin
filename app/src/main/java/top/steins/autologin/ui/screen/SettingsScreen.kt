package top.steins.autologin.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import top.steins.autologin.data.AppearanceMode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import top.steins.autologin.BuildConfig
import top.steins.autologin.network.update.UpdateState
import top.steins.autologin.network.update.UpdateDownloadState
import top.steins.autologin.ui.component.updateDownloadSummary
import top.steins.autologin.ui.component.NavigationTopBar
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.LocalAloginDarkTheme
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.appCardElevation

@Composable
fun SettingsScreen(
    targetWifiCount: Int,
    appearanceMode: AppearanceMode,
    onAppearanceModeChange: (AppearanceMode) -> Unit,
    httpLogEnabled: Boolean,
    logEntryCount: Int,
    updateState: UpdateState,
    onNavigateBack: () -> Unit,
    onNavigateToLog: () -> Unit,
    onNavigateToWifiConfig: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit
) {
    var showAppearanceDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                NavigationTopBar(
                    title = stringResource(R.string.settings_title),
                    onNavigateBack = onNavigateBack
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = ScreenHorizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 目标网络入口
                SettingsOptionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToWifiConfig() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.settings_target_wifi),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                pluralStringResource(
                                    R.plurals.target_wifi_configured_count,
                                    targetWifiCount,
                                    targetWifiCount
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.chevron_right),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // App 显示设置
                SettingsOptionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAppearanceDialog = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.appearance_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                appearanceMode.label(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.chevron_right),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (httpLogEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // HTTP Log 入口仅在关于页解锁后显示。
                    SettingsOptionCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToLog() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    stringResource(R.string.settings_http_log),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    pluralStringResource(
                                        R.plurals.log_entry_count,
                                        logEntryCount,
                                        logEntryCount
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                painter = painterResource(R.drawable.chevron_right),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 关于应用入口
                SettingsOptionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToAbout() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.about_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.about_settings_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.chevron_right),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 检查更新
                SettingsOptionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = updateState !is UpdateState.Checking) {
                                if (updateState is UpdateState.Available) {
                                    onDownloadUpdate()
                                } else {
                                    onCheckForUpdates()
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.check_for_updates),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = when (updateState) {
                                    UpdateState.Idle -> stringResource(
                                        R.string.update_idle,
                                        BuildConfig.VERSION_NAME
                                    )
                                    UpdateState.Checking -> stringResource(R.string.update_checking)
                                    is UpdateState.UpToDate -> stringResource(
                                        R.string.update_up_to_date,
                                        BuildConfig.VERSION_NAME
                                    )
                                    is UpdateState.Available -> if (updateState.download != UpdateDownloadState.None) {
                                        updateDownloadSummary(updateState.download)
                                    } else stringResource(
                                        R.string.update_available,
                                        updateState.update.version
                                    )
                                    is UpdateState.Error -> stringResource(R.string.update_check_failed)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (updateState is UpdateState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.chevron_right),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAppearanceDialog) {
        AlertDialog(
            onDismissRequest = { showAppearanceDialog = false },
            title = { Text(stringResource(R.string.appearance_title)) },
            text = {
                Column {
                    AppearanceMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAppearanceModeChange(mode)
                                    showAppearanceDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = mode == appearanceMode,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(mode.label())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAppearanceDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsOptionCard(content: @Composable ColumnScope.() -> Unit) {
    val isDarkTheme = LocalAloginDarkTheme.current

    // Material 3's non-clickable Card remembers its initial elevation. Recreate it when the
    // effective theme changes so a dark-theme shadow cannot remain after switching to light.
    key(isDarkTheme) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = AppCardShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            elevation = appCardElevation(),
            content = content
        )
    }
}

@Composable
private fun AppearanceMode.label(): String = when (this) {
    AppearanceMode.SYSTEM -> stringResource(R.string.appearance_system)
    AppearanceMode.LIGHT -> stringResource(R.string.appearance_light)
    AppearanceMode.DARK -> stringResource(R.string.appearance_dark)
}

// ==================== 目标 WiFi 配置页 ====================
