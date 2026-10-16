package top.steins.autologin.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import top.steins.autologin.R
import top.steins.autologin.network.AccountDevice
import top.steins.autologin.network.formatFlowMb
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.appCardElevation

@Composable
internal fun EmptyDeviceCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = appCardElevation(),
    ) {
        Text(
            text = stringResource(R.string.home_no_devices),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun DeviceCard(
    device: AccountDevice,
    isCurrentDevice: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = appCardElevation(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = device.macAddress,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = stringResource(
                        R.string.home_device_status,
                        stringResource(R.string.device_status_online)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                val deviceIp = device.ipAddress.ifBlank { stringResource(R.string.value_placeholder) }
                Text(
                    text = stringResource(R.string.home_device_ip, deviceIp),
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
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.delete_device),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
internal fun AccountSummaryRow(username: String, remainingMoneyYuan: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(modifier = Modifier.weight(1f)) {
            InfoLabel(stringResource(R.string.account_username))
            Spacer(modifier = Modifier.height(4.dp))
            InfoValue(username)
        }

        if (remainingMoneyYuan.isNotBlank()) {
            Spacer(modifier = Modifier.width(16.dp))
            Column(horizontalAlignment = Alignment.End) {
                InfoLabel(stringResource(R.string.home_remaining_money))
                Spacer(modifier = Modifier.height(4.dp))
                InfoValue(stringResource(R.string.home_money_yuan, remainingMoneyYuan))
            }
        }
    }
}

@Composable
internal fun InfoLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun InfoValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

internal fun AccountDevice.isCurrentDevice(localIpAddress: String): Boolean =
    ipAddress.isNotBlank() && ipAddress.trim() == localIpAddress.trim()

@Composable
internal fun String.toDisplayFlow(): String =
    takeIf { it.isNotBlank() }?.let(::formatFlowMb)
        ?: stringResource(R.string.value_placeholder)

internal fun calculateFlowUsageFraction(usedFlow: String, remainingFlow: String): Float? {
    val usedMb = usedFlow.toMegabytes() ?: return null
    val remainingMb = remainingFlow.toMegabytes() ?: return null
    val totalMb = usedMb + remainingMb
    if (totalMb <= 0.0) return null
    return (usedMb / totalMb).toFloat().coerceIn(0f, 1f)
}

internal fun String.toMegabytes(): Double? {
    val normalized = trim().uppercase(Locale.ROOT)
    val number = Regex("""\d+(?:\.\d+)?""").find(normalized)?.value?.toDoubleOrNull() ?: return null
    return when {
        normalized.contains("GB") -> number * 1024
        normalized.contains("KB") -> number / 1024
        else -> number
    }
}
