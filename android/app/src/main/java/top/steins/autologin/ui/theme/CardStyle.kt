package top.steins.autologin.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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

val AppShadowElevation = 2.dp

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

fun Modifier.appShadow(
    shape: Shape,
    enabled: Boolean = true,
    alpha: Float = 0.5f
): Modifier = shadow(
    elevation = if (enabled) AppShadowElevation else 0.dp,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = alpha),
    spotColor = Color.Black.copy(alpha = alpha)
)
