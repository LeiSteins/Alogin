package top.steins.autologin.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.appCardElevation

internal data class DeviceListEntry(
    val id: String,
    val macAddress: String,
    val ipAddressLabel: String,
    val isCurrentDevice: Boolean,
    val removeDeviceDescription: String,
    val statusText: String? = null,
    val isOnline: Boolean = false
)

@Composable
internal fun DeviceListCardContent(
    devices: List<DeviceListEntry>,
    title: String,
    emptyText: String,
    animationDurationMillis: Int,
    enabled: Boolean,
    showEmptyState: Boolean = true,
    onRemoveDevice: (DeviceListEntry) -> Unit
) {
    var renderedDevices by remember { mutableStateOf(devices) }
    var isInitialComposition by remember { mutableStateOf(true) }
    val currentDevices by rememberUpdatedState(devices)
    SideEffect {
        isInitialComposition = false
    }

    LaunchedEffect(devices) {
        // 刷新后保留已移除的行，等收缩、淡出完成后再清理。
        val updatedDevices = devices.toMutableList()
        renderedDevices.forEachIndexed { index, device ->
            if (devices.none { it.id == device.id }) {
                updatedDevices.add(index.coerceAtMost(updatedDevices.size), device)
            }
        }
        renderedDevices = updatedDevices
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = appCardElevation(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            renderedDevices.forEach { device ->
                key(device.id) {
                    val visible = devices.any { it.id == device.id }
                    AnimatedDeviceRow(
                        device = device,
                        animationDurationMillis = animationDurationMillis,
                        visible = visible,
                        initiallyVisible = isInitialComposition,
                        isCurrentDevice = device.isCurrentDevice,
                        enabled = enabled && visible,
                        onRemoveDevice = { onRemoveDevice(device) },
                        onHidden = {
                            if (currentDevices.none { it.id == device.id }) {
                                renderedDevices = renderedDevices.filterNot {
                                    it.id == device.id
                                }
                            }
                        }
                    )
                }
            }
            AnimatedVisibility(
                visible = showEmptyState && renderedDevices.isEmpty(),
                enter = expandVertically(tween(animationDurationMillis)) +
                        fadeIn(tween(animationDurationMillis)),
                exit = shrinkVertically(tween(animationDurationMillis)) +
                        fadeOut(tween(animationDurationMillis))
            ) {
                Text(
                    text = emptyText,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AnimatedDeviceRow(
    device: DeviceListEntry,
    animationDurationMillis: Int,
    visible: Boolean,
    initiallyVisible: Boolean,
    isCurrentDevice: Boolean,
    enabled: Boolean,
    onRemoveDevice: () -> Unit,
    onHidden: () -> Unit
) {
    val visibility = remember { MutableTransitionState(initiallyVisible) }
    val currentOnHidden by rememberUpdatedState(onHidden)
    LaunchedEffect(visible) {
        visibility.targetState = visible
    }
    LaunchedEffect(visible, visibility.isIdle, visibility.currentState) {
        if (!visible && visibility.isIdle && !visibility.currentState) {
            currentOnHidden()
        }
    }

    AnimatedVisibility(
        visibleState = visibility,
        enter = expandVertically(tween(animationDurationMillis)) +
                fadeIn(tween(animationDurationMillis)),
        exit = shrinkVertically(tween(animationDurationMillis)) +
                fadeOut(tween(animationDurationMillis))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 按钮触控区和图标画布已有留白，行尾不再叠加内边距。
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = device.macAddress,
                        modifier = Modifier.weight(1f, fill = device.statusText == null),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    device.statusText?.let { statusText ->
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (device.isOnline) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = device.ipAddressLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isCurrentDevice) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.current_device),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onRemoveDevice,
                enabled = enabled,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = device.removeDeviceDescription,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
