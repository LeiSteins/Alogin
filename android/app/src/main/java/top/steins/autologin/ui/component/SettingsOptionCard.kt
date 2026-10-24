package top.steins.autologin.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.LocalAloginDarkTheme
import top.steins.autologin.ui.theme.appCardElevation

@Composable
fun SettingsOptionCard(content: @Composable ColumnScope.() -> Unit) {
    val isDarkTheme = LocalAloginDarkTheme.current

    // Material 3's non-clickable Card remembers its initial elevation. Recreate it when the
    // effective theme changes so a dark-theme shadow cannot remain after switching to light.
    key(isDarkTheme) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = AppCardShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            elevation = appCardElevation(),
            content = content
        )
    }
}
