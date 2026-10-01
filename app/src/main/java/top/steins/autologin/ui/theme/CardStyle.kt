package top.steins.autologin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

val AppCardShape = RoundedCornerShape(24.dp)

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
