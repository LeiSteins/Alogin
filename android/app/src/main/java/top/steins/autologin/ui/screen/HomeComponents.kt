package top.steins.autologin.ui.screen

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.steins.autologin.R
import top.steins.autologin.ui.component.AppearEasing
import top.steins.autologin.ui.component.ScaleFadeBox
import top.steins.autologin.ui.theme.AppCardContentPadding
import top.steins.autologin.ui.theme.AppInfoCardShape
import top.steins.autologin.ui.theme.AppControlShape
import top.steins.autologin.ui.theme.appCardElevation

@Composable
internal fun StaggeredCard(
    visible: Boolean,
    initiallyVisible: Boolean,
    index: Int,
    content: @Composable () -> Unit
) {
    ScaleFadeBox(
        visible = visible,
        modifier = Modifier.fillMaxWidth(),
        initiallyVisible = initiallyVisible,
        durationMillis = CardAnimationDurationMillis,
        // 每张卡片入场完成后，才开始下一张卡片的入场动画。
        enterDelayMillis = index * CardAnimationDurationMillis
    ) {
        content()
    }
}

@Composable
internal fun FloatingActionBox(
    label: String,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = AppControlShape
    // 保持加载中的按钮使用不透明主色，避免禁用态的半透明色叠在不同内容上时呈现出色块。
    val containerColor = if (enabled || isLoading) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (enabled || isLoading) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .height(56.dp)
            .shadow(elevation = 8.dp, shape = shape)
            .clip(shape)
            .background(containerColor)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else {
            Text(
                text = label,
                color = contentColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun NetworkInfoSummary(
    wifiName: String,
    ipAddress: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.height(44.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = wifiName,
            fontSize = 20.sp,
            style = MaterialTheme.typography.labelLarge.copy(lineHeight = 18.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = ipAddress,
            fontSize = 12.sp,
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = 2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun NetworkInfoCard(wifiName: String, ipAddress: String, errorMessage: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = CardAnimationDurationMillis,
                    easing = AppearEasing
                )
            ),
        shape = AppInfoCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = appCardElevation(),
    ) {
        Column(
            modifier = Modifier.padding(AppCardContentPadding),
            horizontalAlignment = Alignment.Start
        ) {
            InfoLabel(stringResource(R.string.home_current_network))
            Spacer(modifier = Modifier.height(4.dp))
            InfoValue(wifiName)
            Spacer(modifier = Modifier.height(16.dp))
            InfoLabel(stringResource(R.string.home_ip_address))
            Spacer(modifier = Modifier.height(4.dp))
            InfoValue(ipAddress)
            if (errorMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
