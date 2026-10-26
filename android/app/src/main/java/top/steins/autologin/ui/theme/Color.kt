package top.steins.autologin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct

val BrandBlue = Color(0xFF0096C3)

internal fun ColorScheme.withSeededSurfaces(seedColor: Color, darkTheme: Boolean): ColorScheme {
    val seed = Hct.fromInt(seedColor.toArgb())
    // Match FlClash's content palette, keeping large surfaces nearly neutral.
    val neutralChroma = seed.chroma / 8.0
    fun neutral(tone: Double, paper: Boolean = false): Color = Color(
        Hct.from(seed.hue, minOf(neutralChroma, if (paper) 1.5 else 3.0), tone).toInt()
    )

    val page = neutral(if (darkTheme) 6.0 else 94.0)
    val card = neutral(if (darkTheme) 15.0 else 98.0, paper = !darkTheme)
    val block = neutral(if (darkTheme) 21.0 else 94.0)
    val inset = neutral(if (darkTheme) 27.0 else 90.0)
    return copy(
        background = page,
        onBackground = neutral(if (darkTheme) 97.0 else 10.0),
        surface = page,
        onSurface = neutral(if (darkTheme) 97.0 else 10.0),
        surfaceVariant = block,
        onSurfaceVariant = neutral(if (darkTheme) 80.0 else 30.0),
        surfaceDim = neutral(if (darkTheme) 6.0 else 87.0),
        surfaceBright = neutral(if (darkTheme) 24.0 else 98.0, paper = !darkTheme),
        surfaceContainerLowest = page,
        surfaceContainerLow = card,
        surfaceContainer = card,
        surfaceContainerHigh = block,
        surfaceContainerHighest = inset
    )
}
