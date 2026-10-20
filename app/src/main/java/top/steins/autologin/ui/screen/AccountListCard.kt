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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import top.steins.autologin.domain.model.SavedAccount
import top.steins.autologin.ui.theme.AppCardShape

private const val AccountRowAnimationDurationMillis = 250

@Composable
internal fun AccountListCard(
    selectedAccountUsername: String,
    accounts: List<SavedAccount>,
    enabled: Boolean,
    onSelectAccount: (String) -> Unit,
    onEditAccount: (SavedAccount) -> Unit
) {
    var renderedAccounts by remember { mutableStateOf(accounts) }
    var isInitialComposition by remember { mutableStateOf(true) }
    val currentAccounts by rememberUpdatedState(accounts)
    SideEffect {
        isInitialComposition = false
    }

    LaunchedEffect(accounts) {
        // 已删除的行保留到收缩动画结束，新增及更新的行使用最新账号数据。
        val updatedAccounts = accounts.toMutableList()
        renderedAccounts.forEachIndexed { index, account ->
            if (accounts.none { it.username == account.username }) {
                updatedAccounts.add(index.coerceAtMost(updatedAccounts.size), account)
            }
        }
        renderedAccounts = updatedAccounts
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            renderedAccounts.forEach { account ->
                key(account.username) {
                    val visible = accounts.any { it.username == account.username }
                    val visibility = remember { MutableTransitionState(isInitialComposition) }
                    LaunchedEffect(visible) {
                        visibility.targetState = visible
                    }
                    LaunchedEffect(visible, visibility.isIdle, visibility.currentState) {
                        if (!visible && visibility.isIdle && !visibility.currentState &&
                            currentAccounts.none { it.username == account.username }
                        ) {
                            renderedAccounts = renderedAccounts.filterNot { it.username == account.username }
                        }
                    }

                    AnimatedVisibility(
                        visibleState = visibility,
                        enter = expandVertically(tween(AccountRowAnimationDurationMillis)) +
                                fadeIn(tween(AccountRowAnimationDurationMillis)),
                        exit = shrinkVertically(tween(AccountRowAnimationDurationMillis)) +
                                fadeOut(tween(AccountRowAnimationDurationMillis))
                    ) {
                        AccountListRow(
                            account = account,
                            isActive = account.username == selectedAccountUsername,
                            enabled = enabled && visible,
                            onSelect = { onSelectAccount(account.username) },
                            onEdit = { onEditAccount(account) }
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = renderedAccounts.isEmpty(),
                enter = expandVertically(tween(AccountRowAnimationDurationMillis)) +
                        fadeIn(tween(AccountRowAnimationDurationMillis)),
                exit = shrinkVertically(tween(AccountRowAnimationDurationMillis)) +
                        fadeOut(tween(AccountRowAnimationDurationMillis))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.account_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.account_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountListRow(
    account: SavedAccount,
    isActive: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth()
            .semantics { selected = isActive }
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (isActive) colors.primaryContainer else colors.surfaceContainerHigh
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Text(
                    account.username.takeLast(2).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isActive) colors.onPrimaryContainer else colors.onSurfaceVariant
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                account.username, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(if (isActive) R.string.account_selected else R.string.account_tap_to_use),
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) colors.primary else colors.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit, enabled = enabled) {
            Icon(painterResource(R.drawable.account_edit), stringResource(R.string.account_edit_named, account.username))
        }
    }
}
