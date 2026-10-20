package top.steins.autologin.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.SeamlessStatus
import top.steins.autologin.ui.component.AppearEasing
import top.steins.autologin.ui.component.DismissEasing
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.appCardElevation

@Composable
internal fun AccountInfoCard(
    overview: AccountOverview?,
    errorMessage: String,
    onRetry: () -> Unit,
    seamlessStatus: SeamlessStatus,
    onNavigateToSeamless: () -> Unit,
    canNavigateToSeamless: Boolean
) {
    val hasError = errorMessage.isNotBlank()
    val contentState = AccountInfoContentState(
        content = when {
            overview != null && hasError -> AccountInfoContent.OverviewWithError
            overview != null -> AccountInfoContent.Overview
            hasError -> AccountInfoContent.Error
            else -> AccountInfoContent.Empty
        },
        overview = overview,
        errorMessage = errorMessage
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = appCardElevation(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_logged_in),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            AnimatedContent(
                targetState = contentState,
                transitionSpec = {
                    (fadeIn(
                        animationSpec = tween(
                            durationMillis = CardAnimationDurationMillis,
                            easing = AppearEasing
                        )
                    ) togetherWith fadeOut(
                        animationSpec = tween(
                            durationMillis = CardAnimationDurationMillis,
                            easing = DismissEasing
                        )
                    )).using(
                        SizeTransform(clip = false) { initialSize, targetSize ->
                            tween(
                                durationMillis = CardAnimationDurationMillis,
                                easing = if (targetSize.height >= initialSize.height) {
                                    AppearEasing
                                } else {
                                    DismissEasing
                                }
                            )
                        }
                    )
                },
                contentKey = { state -> state.content },
                label = "accountInfoContent"
            ) { state ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    state.overview?.let { currentOverview ->
                        AccountSummaryRow(
                            username = currentOverview.username,
                            remainingMoneyYuan = currentOverview.remainingMoneyYuan
                        )
                        FlowUsageSection(
                            usedFlowMb = currentOverview.usedFlowMb,
                            remainingFlowMb = currentOverview.remainingFlowMb
                        )
                    }

                    if (state.errorMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = state.errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.home_retry_fetch))
                        }
                    }
                }
            }
            if (overview != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.seamless_title),
                        modifier = Modifier.alignByBaseline(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(
                            when (seamlessStatus) {
                                SeamlessStatus.Enabled -> R.string.seamless_enabled
                                SeamlessStatus.Disabled -> R.string.seamless_disabled
                                SeamlessStatus.Unknown -> R.string.value_placeholder
                            }
                        ),
                        modifier = Modifier
                            .alignByBaseline()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(
                                enabled = canNavigateToSeamless,
                                role = Role.Button,
                                onClick = onNavigateToSeamless
                            ),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (canNavigateToSeamless) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FlowUsageSection(usedFlowMb: String, remainingFlowMb: String) {
    val usageFraction = calculateFlowUsageFraction(usedFlowMb, remainingFlowMb)

    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = usageFraction?.let {
                stringResource(R.string.home_flow_used_percent, (it * 100).toInt())
            } ?: stringResource(R.string.value_placeholder),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { usageFraction ?: 0f },
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.secondaryContainer,
        drawStopIndicator = {}
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.home_flow_used, usedFlowMb.toDisplayFlow()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.home_flow_remaining, remainingFlowMb.toDisplayFlow()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
