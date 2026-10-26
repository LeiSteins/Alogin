package top.steins.autologin.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.domain.model.AppearanceMode
import top.steins.autologin.ui.screen.SoftwareSettingsScreen

class SurfaceThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchingTheme_updatesPageCardsAndNestedChoices() {
        val dark = mutableStateOf(false)
        var scheme: ColorScheme? = null
        compose.setContent {
            AloginTheme(darkTheme = dark.value) {
                val colors = MaterialTheme.colorScheme
                SideEffect { scheme = colors }
                SoftwareSettingsScreen(
                    appearanceMode = AppearanceMode.SYSTEM,
                    onAppearanceModeChange = {},
                    autoLoginOnStartup = false,
                    onAutoLoginOnStartupChange = {},
                    onNavigateBack = {}
                )
            }
        }
        compose.waitForIdle()
        val lightBackground = checkSurfacesAndCapture("light", checkNotNull(scheme))
        compose.runOnIdle { dark.value = true }
        compose.waitForIdle()
        val darkBackground = checkSurfacesAndCapture("dark", checkNotNull(scheme))
        assertNotEquals(lightBackground, darkBackground)
    }

    private fun checkSurfacesAndCapture(name: String, scheme: ColorScheme): Int {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        listOf(scheme.background, scheme.surfaceContainer, scheme.surfaceContainerHigh).forEach { color ->
            assertTrue("$name surface $color must appear on screen", pixels.count { it == color.toArgb() } > 100)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "theme-previews").apply { mkdirs() }
        File(directory, "software-settings-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return scheme.background.toArgb()
    }
}
