package top.steins.autologin.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import top.steins.autologin.ui.theme.appShadow

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    shadow: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = if (shadow) modifier.appShadow(shape) else modifier, shape = shape, colors = colors, content = content)
}
