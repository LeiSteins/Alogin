package top.steins.autologin.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import top.steins.autologin.R
import top.steins.autologin.network.AccountDevice
import top.steins.autologin.network.formatTrafficMegabytes
import top.steins.autologin.ui.component.DeviceListCardContent
import top.steins.autologin.ui.component.DeviceListEntry

@Composable
internal fun DeviceListCard(
    devices: List<AccountDevice>,
    localIpAddress: String,
    enabled: Boolean,
    showEmptyState: Boolean = true,
    onLogoutDevice: (AccountDevice) -> Unit
) {
    DeviceListCardContent(
        devices = devices.map { device ->
            DeviceListEntry(
                id = device.sessionId,
                macAddress = device.macAddress,
                ipAddressLabel = stringResource(
                    R.string.home_device_ip,
                    device.ipAddress.ifBlank { stringResource(R.string.value_placeholder) }
                ),
                isCurrentDevice = device.isCurrentDevice(localIpAddress),
                removeDeviceDescription = stringResource(R.string.logout_device)
            )
        },
        title = stringResource(R.string.home_online_devices),
        emptyText = stringResource(R.string.home_no_devices),
        animationDurationMillis = CardAnimationDurationMillis,
        enabled = enabled,
        showEmptyState = showEmptyState,
        onRemoveDevice = { entry -> devices.find { it.sessionId == entry.id }?.let(onLogoutDevice) }
    )
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
internal fun String.toDisplayTraffic(): String =
    takeIf { it.isNotBlank() }?.let(::formatTrafficMegabytes)
        ?: stringResource(R.string.value_placeholder)

internal fun calculateTrafficUsageFraction(usedTraffic: String, remainingTraffic: String): Float? {
    val usedMb = usedTraffic.toMegabytes() ?: return null
    val remainingMb = remainingTraffic.toMegabytes() ?: return null
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
