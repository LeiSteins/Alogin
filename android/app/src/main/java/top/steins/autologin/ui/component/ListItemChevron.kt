package top.steins.autologin.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import top.steins.autologin.R
import top.steins.autologin.ui.theme.ListRowIconSize

@Composable
internal fun ListItemChevron() {
    Icon(
        painter = painterResource(R.drawable.chevron_right),
        contentDescription = null,
        modifier = Modifier.size(ListRowIconSize),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
