package top.steins.autologin.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import top.steins.autologin.BuildConfig
import top.steins.autologin.network.update.UpdateState
import top.steins.autologin.network.update.UpdateDownloadState
import top.steins.autologin.ui.component.updateDownloadSummary
import top.steins.autologin.ui.component.SettingsOptionCard
import top.steins.autologin.ui.component.NavigationTopBar
import top.steins.autologin.ui.theme.ScreenHorizontalPadding

@Composable
fun SettingsScreen(
    targetWifiCount: Int,
    httpLogEnabled: Boolean,
    logEntryCount: Int,
    updateState: UpdateState,
    onNavigateBack: () -> Unit,
    onNavigateToLog: () -> Unit,
    onNavigateToWifiConfig: () -> Unit,
    onNavigateToSoftwareSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit
) {
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

                // 软件设置入口
                SettingsOptionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToSoftwareSettings() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.software_settings_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(R.string.software_settings_summary),
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

}
