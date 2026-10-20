package top.steins.autologin.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.ui.component.NavigationTopBar
import top.steins.autologin.ui.component.WifiSsidInput
import top.steins.autologin.ui.theme.AppCardShape
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.appCardElevation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiConfigScreen(
    targetSsids: List<String>,
    onAddTargetWifi: (String) -> Unit,
    onRemoveTargetWifi: (String) -> Unit,
    onShowToast: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    var newSsid by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val focusManager = LocalFocusManager.current
    var showScanSheet by remember { mutableStateOf(false) }
    val scanSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // 已有及显示过的行再次进入视口时，直接显示，避免重复播放新增动画。
    val displayedSsids = remember { targetSsids.toMutableSet() }
    val wifiItemContainerColor = MaterialTheme.colorScheme.surfaceContainer

    fun addNewSsid() {
        val trimmed = newSsid.trim()
        if (trimmed.isNotEmpty()) {
            onAddTargetWifi(trimmed)
            newSsid = ""
            focusManager.clearFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(focusManager) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
    ) {
        Scaffold(
            topBar = {
                NavigationTopBar(
                    title = stringResource(R.string.wifi_config_title),
                    onNavigateBack = onNavigateBack
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item(key = "controls") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (targetSsids.isEmpty()) Modifier.fillParentMaxHeight() else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 添加行
                        WifiSsidInput(
                            value = newSsid,
                            onValueChange = { newSsid = it },
                            onAdd = ::addNewSsid,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ScreenHorizontalPadding)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 扫描附近的 WiFi 按钮
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                showScanSheet = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ScreenHorizontalPadding)
                                .height(54.dp),
                            shape = AppCardShape
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.add_circle),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.wifi_scan_button),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Text(
                            text = stringResource(R.string.wifi_config_description),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = ScreenHorizontalPadding + 4.dp,
                                    vertical = 12.dp
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Start
                        )

                        if (targetSsids.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.wifi_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (targetSsids.isNotEmpty()) {
                    item(key = "wifiList") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ScreenHorizontalPadding),
                            shape = AppCardShape,
                            colors = CardDefaults.cardColors(containerColor = wifiItemContainerColor),
                            elevation = appCardElevation()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                targetSsids.forEach { ssid ->
                                    key(ssid) {
                                        val initiallyVisible = remember { !displayedSsids.add(ssid) }
                                        TargetWifiRow(
                                            ssid = ssid,
                                            initiallyVisible = initiallyVisible,
                                            onRemove = {
                                                displayedSsids.remove(ssid)
                                                onRemoveTargetWifi(ssid)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 扫描结果 BottomSheet
        if (showScanSheet) {
            ModalBottomSheet(
                onDismissRequest = { showScanSheet = false },
                sheetState = scanSheetState,
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = {
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                }
            ) {
                WifiScanSheetContent(
                    configuredSsids = targetSsids.toSet(),
                    onSelectWifi = { ssid ->
                        onAddTargetWifi(ssid)
                        showScanSheet = false
                        scope.launch {
                            onShowToast(resources.getString(R.string.wifi_added, ssid))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TargetWifiRow(
    ssid: String,
    initiallyVisible: Boolean,
    onRemove: () -> Unit
) {
    var deleting by remember { mutableStateOf(false) }
    var removalReported by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val visibility = remember { MutableTransitionState(initiallyVisible) }
    val currentOnRemove by rememberUpdatedState(onRemove)

    LaunchedEffect(deleting) {
        visibility.targetState = !deleting
    }

    // 等整行收缩、淡出完成后，再更新持久化列表。
    LaunchedEffect(deleting, visibility.isIdle, visibility.currentState) {
        if (deleting && visibility.isIdle && !visibility.currentState && !removalReported) {
            removalReported = true
            currentOnRemove()
        }
    }

    // 删除动画期间滚出视口时仍完成删除，并避免回调重复执行。
    DisposableEffect(Unit) {
        onDispose {
            if (deleting && !removalReported) {
                removalReported = true
                currentOnRemove()
            }
        }
    }

    AnimatedVisibility(
        visibleState = visibility,
        enter = expandVertically(tween(250)) + fadeIn(tween(250)),
        exit = shrinkVertically(tween(250)) + fadeOut(tween(250))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = ssid,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    deleting = true
                },
                enabled = !deleting,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = stringResource(R.string.action_delete),
                    // 保留图标尺寸，补偿触控区及矢量画布的留白。
                    modifier = Modifier.size(20.dp).offset(x = 2.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
