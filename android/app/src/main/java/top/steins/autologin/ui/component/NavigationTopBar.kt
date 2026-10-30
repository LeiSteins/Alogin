package top.steins.autologin.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import top.steins.autologin.ui.theme.NavigationTitleTextStyle
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.TopBarHeight

/**
 * 带返回按钮的页面顶栏。尺寸结构与主页顶栏保持一致：状态栏区域之外固定为
 * [TopBarHeight]，避免 Material 组件的内部尺寸变化导致不同层级页面高度不一致。
 */
@Composable
fun NavigationTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .height(TopBarHeight)
            .padding(start = ScreenHorizontalPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavigationBackButton(onClick = onNavigateBack)
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = ScreenHorizontalPadding),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = NavigationTitleTextStyle
        )
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(
                horizontalArrangement = Arrangement.End,
                content = actions
            )
        }
    }
}

@Composable
internal fun NavigationBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String = stringResource(R.string.action_back)
) {
    ElasticIconButton(
        onClick = onClick,
        modifier = modifier.padding(vertical = 2.dp).size(44.dp),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Icon(painterResource(R.drawable.arrow_back), contentDescription = contentDescription)
    }
}
