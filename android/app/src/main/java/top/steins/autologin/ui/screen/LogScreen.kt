package top.steins.autologin.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import top.steins.autologin.domain.model.HttpLogEntry
import top.steins.autologin.domain.model.HttpLogEntryType
import top.steins.autologin.ui.component.NavigationTopBar
import top.steins.autologin.ui.theme.ScreenHorizontalPadding

// ==================== HTTP 日志页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    entries: List<HttpLogEntry>,
    onClearLogs: () -> Unit,
    onDisableHttpLog: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val sortedEntries = remember(entries) {
        entries.sortedWith(
            compareByDescending<HttpLogEntry> { it.timestamp }
                .thenByDescending { it.id }
        )
    }
    var selectedEntry by remember { mutableStateOf<HttpLogEntry?>(null) }
    var showDisableConfirmation by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Scaffold(
        topBar = {
            NavigationTopBar(
                title = stringResource(R.string.log_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = {
                            onClearLogs()
                            selectedEntry = null
                        },
                        enabled = entries.isNotEmpty()
                    ) {
                        Icon(
                            painterResource(R.drawable.delete),
                            contentDescription = stringResource(R.string.log_clear)
                        )
                    }
                    IconButton(onClick = { showDisableConfirmation = true }) {
                        Icon(
                            painterResource(R.drawable.toggle_off),
                            contentDescription = stringResource(R.string.log_disable)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.log_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = ScreenHorizontalPadding,
                    vertical = 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sortedEntries, key = { it.id }) { entry ->
                    LogEntryCard(
                        entry = entry,
                        onClick = { selectedEntry = entry }
                    )
                }
            }
        }
    }

    // 详情底部弹窗
    selectedEntry?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { selectedEntry = null },
            sheetState = sheetState
        ) {
            LogEntryDetail(entry = entry)
        }
    }

    if (showDisableConfirmation) {
        AlertDialog(
            onDismissRequest = { showDisableConfirmation = false },
            title = { Text(stringResource(R.string.log_disable_title)) },
            text = { Text(stringResource(R.string.log_disable_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableConfirmation = false
                        selectedEntry = null
                        onDisableHttpLog()
                        onNavigateBack()
                    }
                ) {
                    Text(stringResource(R.string.log_disable_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
