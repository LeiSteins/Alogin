package top.steins.autologin.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val LocalIconButtonGroupInteractions = staticCompositionLocalOf<MutableInteractionSource?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElasticIconButtonGroup(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable RowScope.() -> Unit
) {
    val interactions = remember { MutableInteractionSource() }
    CompositionLocalProvider(
        LocalIconButtonGroupInteractions provides interactions,
        LocalMinimumInteractiveComponentSize provides 0.dp
    ) {
        Row(
            modifier = modifier
                .elasticPress(interactions, enabled = true, shape = CircleShape)
                .background(containerColor, CircleShape),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
