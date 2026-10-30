package top.steins.autologin.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

val AppInfoCardShape: Shape = SmoothCardShape(34.dp)
val AppListCardShape: Shape = SmoothCardShape(24.dp)
val AppControlShape: Shape = AppListCardShape
val AppSheetShape: Shape = SmoothCardShape(28.dp, topOnly = true)
val AppCardContentPadding = PaddingValues(horizontal = CardHorizontalPadding, vertical = CardVerticalPadding)

private class SmoothCardShape(private val cornerRadius: Dp, private val topOnly: Boolean = false) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.minDimension <= 0f) return Outline.Rectangle(Rect(Offset.Zero, size))
        val radius = with(density) { cornerRadius.toPx() }.coerceAtMost(size.minDimension / 2f)
        val polygon = RoundedPolygon(
            vertices = floatArrayOf(0f, 0f, size.width, 0f, size.width, size.height, 0f, size.height),
            centerX = size.width / 2f,
            centerY = size.height / 2f,
            rounding = CornerRounding(radius = radius, smoothing = 0.6f),
            perVertexRounding = if (topOnly) listOf(
                CornerRounding(radius, 0.6f), CornerRounding(radius, 0.6f),
                CornerRounding.Unrounded, CornerRounding.Unrounded
            ) else null
        )
        return Outline.Generic(polygon.toPath().asComposePath())
    }
}

@Composable
fun appCardElevation(): CardElevation {
    val isDark = LocalAloginDarkTheme.current
    return CardDefaults.cardElevation(
        defaultElevation = if (isDark) 2.dp else 0.dp,
        pressedElevation = if (isDark) 2.dp else 0.dp,
        focusedElevation = if (isDark) 2.dp else 0.dp,
        hoveredElevation = if (isDark) 2.dp else 0.dp,
        draggedElevation = if (isDark) 3.dp else 0.dp
    )
}
