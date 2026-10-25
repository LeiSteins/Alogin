package top.steins.autologin.ui.component

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import top.steins.autologin.ui.theme.AppControlShape
import top.steins.autologin.ui.theme.LocalAloginDarkTheme

@Composable
fun WifiSsidInput(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val containerColor = if (LocalAloginDarkTheme.current) {
        colors.surfaceContainerHigh
    } else {
        colors.surfaceContainer
    }
    val label = stringResource(R.string.wifi_add_ssid_label)
    val outline by animateColorAsState(
        targetValue = if (focused) colors.primary else colors.outlineVariant.copy(alpha = 0.4f),
        label = "wifiSsidInputOutline"
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onAdd() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }
                .onPreviewKeyEvent {
                    if (it.key == Key.Enter) {
                        if (it.type == KeyEventType.KeyUp) onAdd()
                        true
                    } else false
                },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(containerColor, AppControlShape)
                        .border(if (focused) 1.5.dp else 1.dp, outline, AppControlShape)
                        .heightIn(min = 60.dp)
                        .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f).padding(end = 8.dp)) {
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.wifi_ssid_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                    ElasticIconButton(onClick = onAdd, enabled = value.isNotBlank()) {
                        Icon(
                            painter = painterResource(R.drawable.add_circle),
                            contentDescription = stringResource(R.string.action_add),
                            modifier = Modifier.size(28.dp),
                            tint = if (value.isNotBlank()) colors.primary
                                else colors.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        )
    }
}
