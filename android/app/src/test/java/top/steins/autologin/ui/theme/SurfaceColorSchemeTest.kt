package top.steins.autologin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceColorSchemeTest {
    private val seeds = listOf(BrandBlue, Color(0xFFD44835), Color(0xFF458453), Color(0xFF888888))

    @Test
    fun lightSurfaces_alternatePageAndPaperWithoutPureWhite() {
        seeds.forEach { seed ->
            val colors = lightColorScheme().withSeededSurfaces(seed, darkTheme = false)
            assertTone(94.0, colors.background)
            assertTone(98.0, colors.surfaceContainer)
            assertTone(90.0, colors.surfaceContainerHighest)
            assertEquals(colors.background, colors.surfaceContainerHigh)
            assertNotEquals(Color.White, colors.surfaceContainer)
        }
    }

    @Test
    fun darkSurfaces_getLighterAtEachDepth() {
        seeds.forEach { seed ->
            val colors = darkColorScheme().withSeededSurfaces(seed, darkTheme = true)
            val layers = surfaces(colors)
            listOf(6.0, 15.0, 21.0, 27.0).zip(layers).forEach { (tone, color) ->
                assertTone(tone, color)
            }
            layers.zipWithNext().forEach { (below, above) ->
                assertTrue(above.luminance() > below.luminance())
            }
        }
    }

    @Test
    fun changingSeed_changesEverySurfaceInBothModesAndKeepsLowChroma() {
        listOf(false, true).forEach { dark ->
            val base = if (dark) darkColorScheme() else lightColorScheme()
            val blue = base.withSeededSurfaces(seeds[0], dark)
            val red = base.withSeededSurfaces(seeds[1], dark)
            surfaces(blue).zip(surfaces(red)).forEach { (blueSurface, redSurface) ->
                assertNotEquals(blueSurface, redSurface)
            }
            seeds.forEach { seed ->
                surfaces(base.withSeededSurfaces(seed, dark)).forEach { surface ->
                    assertTrue(Hct.fromInt(surface.toArgb()).chroma < 4.0)
                }
            }
        }
    }

    @Test
    fun foregrounds_remainReadableAcrossEverySurfaceAndPreserveStatusColors() {
        listOf(false, true).forEach { dark ->
            val base = if (dark) darkColorScheme() else lightColorScheme()
            seeds.forEach { seed ->
                val colors = base.withSeededSurfaces(seed, dark)
                assertEquals(base.primary, colors.primary)
                assertEquals(base.error, colors.error)
                assertEquals(base.primaryContainer, colors.primaryContainer)
                assertEquals(colors.background, colors.surface)
                surfaces(colors).forEach { surface ->
                    assertTrue(contrast(colors.onSurface, surface) >= 4.5)
                    assertTrue(contrast(colors.onSurfaceVariant, surface) >= 4.5)
                }
            }
        }
    }

    private fun surfaces(colors: ColorScheme): List<Color> = listOf(
        colors.background, colors.surfaceContainer, colors.surfaceContainerHigh, colors.surfaceContainerHighest
    )

    private fun assertTone(expected: Double, color: Color) {
        assertEquals(expected, Hct.fromInt(color.toArgb()).tone, 0.3)
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
