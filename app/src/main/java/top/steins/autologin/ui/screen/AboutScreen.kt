package top.steins.autologin.ui.screen

import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.steins.autologin.BuildConfig
import top.steins.autologin.R
import top.steins.autologin.ui.component.ListItemChevron
import top.steins.autologin.ui.component.NavigationTopBar
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.appCardElevation

private const val PROJECT_URL = "https://steins.top/alogin/"
private const val SOURCE_CODE_URL = "https://github.com/LeiSteins/Alogin"
private const val LICENSE_URL = "$SOURCE_CODE_URL/blob/main/LICENSE"

@Composable
fun AboutScreen(
    httpLogEnabled: Boolean,
    onVersionClick: () -> Boolean,
    onShowToast: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val httpLogEnabledMessage = stringResource(R.string.about_http_log_enabled)
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                NavigationTopBar(
                    title = stringResource(R.string.about_title),
                    onNavigateBack = onNavigateBack
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = ScreenHorizontalPadding,
                    top = 16.dp,
                    end = ScreenHorizontalPadding,
                    bottom = 24.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    AndroidView(
                        factory = { context ->
                            ImageView(context).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                setImageDrawable(
                                    context.applicationInfo.loadIcon(context.packageManager)
                                )
                            }
                        },
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(20.dp))
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.about_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppCardShape,
                        colors = cardColors,
                        elevation = appCardElevation()
                    ) {
                        AboutInfoRow(
                            label = stringResource(R.string.about_app_name_label),
                            value = stringResource(R.string.app_name)
                        )
                        AboutInfoRow(
                            label = stringResource(R.string.about_version_label),
                            value = stringResource(
                                R.string.about_version_value,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE
                            ),
                            onClick = {
                                if (!httpLogEnabled && onVersionClick()) {
                                    onShowToast(httpLogEnabledMessage)
                                }
                            }
                        )
                        AboutInfoRow(
                            label = stringResource(R.string.about_package_label),
                            value = BuildConfig.APPLICATION_ID
                        )
                        AboutInfoRow(
                            label = stringResource(R.string.about_license_label),
                            value = stringResource(R.string.about_license_value)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppCardShape,
                        colors = cardColors,
                        elevation = appCardElevation()
                    ) {
                        AboutLinkRow(
                            title = stringResource(R.string.about_project_homepage),
                            summary = stringResource(R.string.about_project_homepage_summary),
                            onClick = { uriHandler.openUri(PROJECT_URL) }
                        )
                        AboutLinkRow(
                            title = stringResource(R.string.about_source_code),
                            summary = stringResource(R.string.about_source_code_summary),
                            onClick = { uriHandler.openUri(SOURCE_CODE_URL) }
                        )
                        AboutLinkRow(
                            title = stringResource(R.string.about_license_details),
                            summary = stringResource(R.string.about_license_details_summary),
                            onClick = { uriHandler.openUri(LICENSE_URL) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutInfoRow(
    label: String,
    value: String,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun AboutLinkRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ListItemChevron()
    }
}
