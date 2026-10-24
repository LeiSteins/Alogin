package top.steins.autologin.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color(0xFF001D36),
    primaryContainer = Color(0xFF0D3B66),
    onPrimaryContainer = Color(0xFFD7E9FF),
    secondary = Color(0xFFBFC7D5),
    onSecondary = Color(0xFF29313D),
    secondaryContainer = Color(0xFF3F4754),
    onSecondaryContainer = Color(0xFFDCE3F1),
    tertiary = Color(0xFFFFB77C),
    onTertiary = Color(0xFF482300),
    tertiaryContainer = Color(0xFF663500),
    onTertiaryContainer = Color(0xFFFFDCC1),
    background = DarkBackground,
    onBackground = Color(0xFFE5E5EA),
    surface = DarkPrimaryCard,
    onSurface = Color(0xFFE5E5EA),
    surfaceVariant = DarkSecondaryCard,
    onSurfaceVariant = Color(0xFFC7C7CC),
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkPrimaryCard,
    surfaceContainer = DarkPrimaryCard,
    surfaceContainerHigh = DarkSecondaryCard,
    surfaceContainerHighest = DarkSecondaryCard,
    outline = Color(0xFF6C6C70),
    outlineVariant = Color(0xFF3A3A3C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF5B6472),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFE3EE),
    onSecondaryContainer = Color(0xFF181C22),
    tertiary = Color(0xFF8F4F00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC1),
    onTertiaryContainer = Color(0xFF2E1500),
    background = LightBackground,
    onBackground = Color(0xFF1C1C1E),
    surface = LightPrimaryCard,
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = LightSecondaryCard,
    onSurfaceVariant = Color(0xFF5F5F66),
    surfaceContainerLowest = LightBackground,
    surfaceContainerLow = LightPrimaryCard,
    surfaceContainer = LightPrimaryCard,
    surfaceContainerHigh = LightSecondaryCard,
    surfaceContainerHighest = LightSecondaryCard,
    outline = Color(0xFF7A7A80),
    outlineVariant = Color(0xFFD8D7DD),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

val LocalAloginDarkTheme = staticCompositionLocalOf { false }

@Composable
fun AloginTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAloginDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
