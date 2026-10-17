package top.steins.autologin.ui.component

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import top.steins.autologin.R

@Composable
internal fun ListItemChevron() {
    val iconSize = 20.dp
    Icon(
        painter = painterResource(R.drawable.chevron_right),
        contentDescription = null,
        modifier = Modifier
            .size(iconSize)
            // 箭头路径右侧留白占画布的 8/24，抵消后与列表文字的边距一致。
            .offset(x = iconSize / 3),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
