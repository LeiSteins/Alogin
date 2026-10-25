package top.steins.autologin.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt
import top.steins.autologin.ui.theme.ListRowHorizontalPadding
import top.steins.autologin.ui.theme.ListRowSeparatorEndPadding

@Composable
internal fun Modifier.listRowSeparator(
    visible: Boolean = true,
    startIndent: Dp = ListRowHorizontalPadding
): Modifier {
    if (!visible) return this
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    var topInRoot by remember { mutableFloatStateOf(0f) }
    return onGloballyPositioned { topInRoot = it.positionInRoot().y }
        .drawWithContent {
            drawContent()
            val thickness = 0.75.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
            if (size.height < thickness) return@drawWithContent
            val start = startIndent.toPx()
            val end = ListRowSeparatorEndPadding.toPx()
            val left = if (layoutDirection == LayoutDirection.Ltr) start else end
            val right = size.width - if (layoutDirection == LayoutDirection.Ltr) end else start
            // Align both edges to physical pixels, including when the list is scrolled.
            val bottom = floor(topInRoot + size.height) - topInRoot
            if (right > left) {
                drawRect(color, Offset(left, bottom - thickness), Size(right - left, thickness))
            }
        }
}
