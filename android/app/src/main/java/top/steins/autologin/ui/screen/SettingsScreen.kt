package top.steins.autologin.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import top.steins.autologin.ui.component.ListItemChevron
import top.steins.autologin.ui.component.NavigationTopBar
import top.steins.autologin.ui.component.SettingsOptionCard
import top.steins.autologin.ui.theme.ScreenHorizontalPadding

private const val SettingsEntryAnimationDurationMillis = 250

@Composable
fun SettingsScreen(
    targetWifiCount: Int,
    httpLogEnabled: Boolean,
    logEntryCount: Int,
    onNavigateBack: () -> Unit,
    onNavigateToLog: () -> Unit,
    onNavigateToWifiConfig: () -> Unit,
    onNavigateToSoftwareSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    animateHttpLogEntry: Boolean = false,
    isPageResumed: Boolean = true,
    onHttpLogEntryAnimationFinished: () -> Unit = {}
) {
    val httpLogVisibility = remember {
        MutableTransitionState(if (animateHttpLogEntry) !httpLogEnabled else httpLogEnabled)
    }
    val currentOnAnimationFinished by rememberUpdatedState(onHttpLogEntryAnimationFinished)
    LaunchedEffect(httpLogEnabled, animateHttpLogEntry, isPageResumed) {
        // 等页面返回转场结束后再插入或移除，避免在其他页面背后提前播放。
        if (!animateHttpLogEntry || isPageResumed) {
            httpLogVisibility.targetState = httpLogEnabled
        }
    }
    LaunchedEffect(
        httpLogVisibility.isIdle,
        httpLogVisibility.currentState,
        httpLogEnabled,
        animateHttpLogEntry,
        isPageResumed
    ) {
        if (animateHttpLogEntry && isPageResumed &&
            httpLogVisibility.isIdle && httpLogVisibility.currentState == httpLogEnabled
        ) {
            currentOnAnimationFinished()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                NavigationTopBar(
                    title = stringResource(R.string.settings_title),
                    onNavigateBack = onNavigateBack
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding)
            ) {
                item(key = "settings") {
                    SettingsOptionCard {
                        SettingsEntryRow(
                            title = stringResource(R.string.settings_target_wifi),
                            summary = pluralStringResource(
                                R.plurals.target_wifi_configured_count,
                                targetWifiCount,
                                targetWifiCount
                            ),
                            onClick = onNavigateToWifiConfig
                        )
                        SettingsEntryRow(
                            title = stringResource(R.string.software_settings_title),
                            summary = stringResource(R.string.software_settings_summary),
                            onClick = onNavigateToSoftwareSettings
                        )
                        // HTTP Log 入口仅在关于页解锁后显示。
                        AnimatedVisibility(
                            visibleState = httpLogVisibility,
                            enter = expandVertically(tween(SettingsEntryAnimationDurationMillis)) +
                                    fadeIn(tween(SettingsEntryAnimationDurationMillis)),
                            exit = shrinkVertically(tween(SettingsEntryAnimationDurationMillis)) +
                                    fadeOut(tween(SettingsEntryAnimationDurationMillis))
                        ) {
                            SettingsEntryRow(
                                title = stringResource(R.string.settings_http_log),
                                summary = pluralStringResource(
                                    R.plurals.log_entry_count,
                                    logEntryCount,
                                    logEntryCount
                                ),
                                onClick = onNavigateToLog,
                                enabled = httpLogEnabled
                            )
                        }
                        SettingsEntryRow(
                            title = stringResource(R.string.about_title),
                            summary = stringResource(R.string.about_settings_summary),
                            onClick = onNavigateToAbout
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsEntryRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ListItemChevron()
    }
}
