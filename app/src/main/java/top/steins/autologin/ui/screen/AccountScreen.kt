package top.steins.autologin.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.data.CredentialSaveResult
import top.steins.autologin.data.SavedAccount
import top.steins.autologin.ui.component.NavigationTopBar
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.ScreenHorizontalPadding

@Composable
fun AccountScreen(
    username: String,
    accounts: List<SavedAccount>,
    onSaveCredentials: suspend (String, String) -> CredentialSaveResult,
    onSelectAccount: suspend (String) -> CredentialSaveResult,
    onRemoveAccount: suspend (String) -> CredentialSaveResult,
    onShowToast: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<SavedAccount?>(null) }
    var deletingAccount by remember { mutableStateOf<String?>(null) }
    var isMutating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme

    fun report(result: CredentialSaveResult, success: Int): Boolean {
        onShowToast(resources.getString(when (result) {
            CredentialSaveResult.SAVED -> success
            CredentialSaveResult.ENCRYPTION_UNAVAILABLE -> R.string.account_save_encryption_unavailable
            CredentialSaveResult.STORAGE_UNAVAILABLE -> R.string.account_save_storage_unavailable
            CredentialSaveResult.INVALID_INPUT -> R.string.account_fields_required
        }))
        return result == CredentialSaveResult.SAVED
    }

    Scaffold(topBar = {
        NavigationTopBar(stringResource(R.string.account_title), onNavigateBack)
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                horizontal = ScreenHorizontalPadding
            ),
        ) {
            item {
                Card(shape = AppCardShape) {
                    Column(
                        modifier = Modifier.fillMaxWidth().background(
                            Brush.linearGradient(listOf(colors.primaryContainer, colors.surfaceContainer))
                        ).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            painterResource(R.drawable.manage_accounts), null,
                            tint = colors.primary, modifier = Modifier.size(36.dp)
                        )
                        Text(
                            stringResource(R.string.account_current),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            username.ifBlank { stringResource(R.string.account_welcome) },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.account_switch_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.account_saved_count, accounts.size),
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { editingAccount = null; showEditor = true }) {
                        Icon(painterResource(R.drawable.add_circle), null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.account_add))
                    }
                }
            }
            item(key = "accountList") {
                AccountListCard(
                    username = username,
                    accounts = accounts,
                    enabled = !isMutating,
                    onSelectAccount = { accountUsername ->
                        if (accountUsername != username) {
                            isMutating = true
                            scope.launch {
                                try {
                                    report(onSelectAccount(accountUsername), R.string.account_switched)
                                } finally {
                                    isMutating = false
                                }
                            }
                        }
                    },
                    onEditAccount = { account -> editingAccount = account; showEditor = true }
                )
            }
            item {
                Text(
                    stringResource(R.string.account_storage_hint),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
    if (showEditor) {
        AccountEditorSheet(
            account = editingAccount,
            existingUsernames = accounts.map { it.username },
            onDismiss = { showEditor = false },
            onDelete = { deletingAccount = editingAccount?.username },
            onSave = { user, pass ->
                if (isMutating) false else {
                    isMutating = true
                    try {
                        report(onSaveCredentials(user, pass), R.string.account_saved_success)
                    } finally {
                        isMutating = false
                    }
                }
            }
        )
    }
    deletingAccount?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!isMutating) deletingAccount = null },
            title = { Text(stringResource(R.string.account_delete_title)) },
            text = { Text(stringResource(R.string.account_delete_message, target)) },
            confirmButton = {
                TextButton(enabled = !isMutating, onClick = {
                    isMutating = true
                    scope.launch {
                        try {
                            if (report(onRemoveAccount(target), R.string.account_deleted)) {
                                deletingAccount = null
                                showEditor = false
                            }
                        } finally {
                            isMutating = false
                        }
                    }
                }) { Text(stringResource(R.string.action_delete), color = colors.error) }
            },
            dismissButton = {
                TextButton(enabled = !isMutating, onClick = { deletingAccount = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
