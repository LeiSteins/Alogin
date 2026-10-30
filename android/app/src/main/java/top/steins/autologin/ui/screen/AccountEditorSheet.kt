package top.steins.autologin.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.ui.theme.AppControlShape
import top.steins.autologin.domain.model.SavedAccount
import top.steins.autologin.ui.component.ElasticButton
import top.steins.autologin.ui.component.ElasticIconButton
import top.steins.autologin.ui.component.ElasticTextButton
import top.steins.autologin.ui.component.PasswordRevealState
import top.steins.autologin.ui.component.PasswordRevealTransformation
import top.steins.autologin.ui.component.AppSheet
import top.steins.autologin.ui.component.rememberAppSheetState
import top.steins.autologin.ui.component.sheetScrollable

@Composable
internal fun AccountEditorSheet(
    account: SavedAccount?,
    existingUsernames: List<String>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: suspend (String, String) -> Boolean
) {
    // 密码仅保留在当前组合内，不写入 SavedInstanceState。
    var username by remember { mutableStateOf(account?.username.orEmpty()) }
    var password by remember { mutableStateOf(account?.password.orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val sheetState = rememberAppSheetState()
    val scrollState = rememberScrollState()
    var showUnsavedChanges by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val passwordReveal = remember(scope) { PasswordRevealState(scope) }
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val duplicate = !isClosing && !isSaving && account == null && username.trim() in existingUsernames
    val usernameError = (attempted && username.isBlank()) || duplicate
    val passwordError = attempted && password.isBlank()

    fun save() {
        if (isClosing || isSaving) return
        attempted = true
        if (username.isNotBlank() && password.isNotBlank() && !duplicate) {
            passwordVisible = false
            passwordReveal.hide()
            focusManager.clearFocus()
            isSaving = true
            scope.launch {
                try {
                    if (onSave(username.trim(), password)) {
                        isClosing = true
                        sheetState.hide()
                        onDismiss()
                    }
                } finally {
                    isSaving = false
                    isClosing = false
                }
            }
        }
    }

    AppSheet(
        title = stringResource(if (account == null) R.string.account_add else R.string.account_edit),
        onDismissRequest = { if (!isSaving) onDismiss() },
        state = sheetState,
        dismissEnabled = !isSaving && !isClosing,
        confirmDismiss = {
            val changed = username != account?.username.orEmpty() || password != account?.password.orEmpty()
            if (changed) {
                passwordVisible = false
                passwordReveal.hide()
                focusManager.clearFocus()
                showUnsavedChanges = true
            }
            !changed
        },
        actions = {
            if (account != null) {
                ElasticIconButton(
                    onClick = {
                        focusManager.clearFocus()
                        onDelete()
                    },
                    modifier = Modifier.padding(vertical = 2.dp).size(44.dp),
                    enabled = !isClosing && !isSaving,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        painterResource(R.drawable.account_trash),
                        contentDescription = stringResource(R.string.account_delete_named, account.username)
                    )
                }
            }
        }
    ) { contentPadding ->
        Column(
            Modifier.fillMaxWidth().sheetScrollable { scrollState.canScrollBackward }.verticalScroll(scrollState)
                .padding(contentPadding).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (account == null) R.string.account_form_hint else R.string.account_edit_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            AccountInput(
                value = username,
                onValueChange = { username = it },
                label = stringResource(R.string.account_username),
                placeholder = stringResource(R.string.account_username_hint),
                icon = R.drawable.manage_accounts,
                readOnly = account != null || isSaving,
                isError = usernameError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                onAction = { passwordFocus.requestFocus() }
            )
            if (duplicate) {
                Text(stringResource(R.string.account_duplicate), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            AccountInput(
                value = password,
                readOnly = isSaving,
                onValueChange = {
                    passwordReveal.onPasswordChanged(
                        previous = password,
                        current = it,
                        enabled = account == null && !passwordVisible
                    )
                    password = it
                },
                label = stringResource(R.string.account_password),
                placeholder = stringResource(R.string.account_password_hint),
                icon = R.drawable.account_lock,
                isError = passwordError,
                modifier = Modifier.focusRequester(passwordFocus).onFocusChanged {
                    if (!it.isFocused) passwordReveal.hide()
                },
                isPassword = true,
                transformation = when {
                    account != null -> PasswordVisualTransformation()
                    passwordVisible -> VisualTransformation.None
                    else -> PasswordRevealTransformation(passwordReveal.revealedRange)
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                    autoCorrectEnabled = false
                ),
                onAction = ::save,
                trailing = if (account == null) {
                    {
                        ElasticIconButton(onClick = {
                            passwordReveal.hide()
                            passwordVisible = !passwordVisible
                        }) {
                            Icon(
                                painterResource(
                                    if (passwordVisible) R.drawable.account_visibility_off
                                    else R.drawable.account_visibility
                                ),
                                stringResource(
                                    if (passwordVisible) R.string.account_hide_password
                                    else R.string.account_show_password
                                ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else null
            )
            if (attempted && (username.isBlank() || password.isBlank())) {
                Text(stringResource(R.string.account_fields_required), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            ElasticButton(
                onClick = ::save,
                enabled = !isClosing && !isSaving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = AppControlShape
            ) {
                Text(stringResource(R.string.account_save_and_use), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
    if (showUnsavedChanges) {
        AlertDialog(
            onDismissRequest = { showUnsavedChanges = false },
            title = { Text(stringResource(R.string.sheet_unsaved_title)) },
            text = { Text(stringResource(R.string.sheet_unsaved_message)) },
            confirmButton = {
                ElasticTextButton(onClick = { showUnsavedChanges = false; save() }) {
                    Text(stringResource(R.string.sheet_save))
                }
            },
            dismissButton = {
                Row {
                    ElasticTextButton(onClick = { showUnsavedChanges = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                    ElasticTextButton(onClick = {
                        showUnsavedChanges = false
                        isClosing = true
                        scope.launch { sheetState.hide(); onDismiss() }
                    }) { Text(stringResource(R.string.sheet_discard), color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}

@Composable
private fun AccountInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: Int,
    keyboardOptions: KeyboardOptions,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    isError: Boolean = false,
    isPassword: Boolean = false,
    transformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val outline by animateColorAsState(
        when {
            isError -> colors.error
            focused -> colors.primary
            else -> colors.outlineVariant.copy(alpha = 0.4f)
        }, label = "accountInputOutline"
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = transformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
            modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = label
                    if (isPassword) password()
                }
                .onPreviewKeyEvent {
                    if (it.key == Key.Enter) {
                        if (it.type == KeyEventType.KeyUp) onAction()
                        true
                    } else false
                },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(colors.surfaceContainer, AppControlShape)
                        .border(if (focused || isError) 1.5.dp else 1.dp, outline, AppControlShape)
                        .heightIn(min = 60.dp).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(painterResource(icon), null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                    Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        if (value.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        }
                        innerTextField()
                    }
                    trailing?.invoke()
                }
            }
        )
    }
}
