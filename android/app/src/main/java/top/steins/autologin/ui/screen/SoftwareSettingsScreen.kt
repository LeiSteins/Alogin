package top.steins.autologin.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.steins.autologin.R
import top.steins.autologin.domain.model.AppearanceMode
import top.steins.autologin.ui.component.ElasticOutlinedButton
import top.steins.autologin.ui.component.ElasticSwitch
import top.steins.autologin.ui.component.NavigationTopBar
import top.steins.autologin.ui.component.SettingsOptionCard
import top.steins.autologin.ui.theme.AppControlShape
import top.steins.autologin.ui.theme.CardHorizontalPadding
import top.steins.autologin.ui.theme.CardVerticalPadding
import top.steins.autologin.ui.theme.ListRowHorizontalPadding
import top.steins.autologin.ui.theme.ListItemTitleTextStyle
import top.steins.autologin.ui.theme.ListRowMinHeight
import top.steins.autologin.ui.theme.ListRowVerticalPadding
import top.steins.autologin.ui.theme.ScreenContentTopPadding
import top.steins.autologin.ui.theme.ScreenHorizontalPadding

@Composable
fun SoftwareSettingsScreen(
    appearanceMode: AppearanceMode,
    onAppearanceModeChange: (AppearanceMode) -> Unit,
    autoLoginOnStartup: Boolean,
    onAutoLoginOnStartupChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            NavigationTopBar(
                title = stringResource(R.string.software_settings_title),
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenHorizontalPadding)
                .padding(top = ScreenContentTopPadding, bottom = 16.dp)
        ) {
            SettingsOptionCard {
                Text(
                    text = stringResource(R.string.appearance_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        start = CardHorizontalPadding, end = CardHorizontalPadding,
                        top = CardVerticalPadding, bottom = 14.dp
                    )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = CardHorizontalPadding, end = CardHorizontalPadding, bottom = CardVerticalPadding)
                        .selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppearanceMode.entries.forEach { mode ->
                        val isSelected = mode == appearanceMode
                        ElasticOutlinedButton(
                            onClick = { onAppearanceModeChange(mode) },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { selected = isSelected },
                            shape = AppControlShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            ),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    painter = painterResource(
                                        when (mode) {
                                            AppearanceMode.SYSTEM -> R.drawable.round_tsunami
                                            AppearanceMode.LIGHT -> R.drawable.round_light
                                            AppearanceMode.DARK -> R.drawable.round_nights
                                        }
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = mode.label(),
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            SettingsOptionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ListRowMinHeight)
                        .toggleable(
                            value = autoLoginOnStartup,
                            role = Role.Switch,
                            onValueChange = onAutoLoginOnStartupChange
                        )
                        .padding(horizontal = ListRowHorizontalPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.auto_login_on_startup),
                        style = ListItemTitleTextStyle,
                        modifier = Modifier.weight(1f).padding(vertical = ListRowVerticalPadding)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    ElasticSwitch(checked = autoLoginOnStartup, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun AppearanceMode.label(): String = when (this) {
    AppearanceMode.SYSTEM -> stringResource(R.string.appearance_system)
    AppearanceMode.LIGHT -> stringResource(R.string.appearance_light)
    AppearanceMode.DARK -> stringResource(R.string.appearance_dark)
}
