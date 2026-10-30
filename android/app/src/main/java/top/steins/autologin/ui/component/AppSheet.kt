package top.steins.autologin.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import top.steins.autologin.R
import top.steins.autologin.ui.theme.AppSheetShape
import top.steins.autologin.ui.theme.LocalAloginDarkTheme
import top.steins.autologin.ui.theme.NavigationTitleTextStyle
import top.steins.autologin.ui.theme.ScreenHorizontalPadding
import top.steins.autologin.ui.theme.SheetContentTopPadding
import kotlin.math.max

private val LocalSheet = staticCompositionLocalOf<AppSheetState?> { null }
internal val SheetHeaderHeight = 68.dp

@Composable
internal fun SheetActions(actions: @Composable RowScope.() -> Unit) {
    val state = LocalSheet.current ?: return
    val current by rememberUpdatedState(actions)
    DisposableEffect(state) {
        val slot: @Composable RowScope.() -> Unit = { current() }
        state.headerActions = slot
        onDispose { if (state.headerActions === slot) state.headerActions = null }
    }
}

/** A modal owns its geometry; its body supplies one scrolling surface. */
@Composable
fun AppSheet(
    title: String,
    onDismissRequest: () -> Unit,
    state: AppSheetState = rememberAppSheetState(),
    dismissEnabled: Boolean = true,
    confirmDismiss: () -> Boolean = { true },
    imeInsets: WindowInsets = WindowInsets.ime,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val parent = LocalSheet.current
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val currentConfirm by rememberUpdatedState(confirmDismiss)
    val currentEnabled by rememberUpdatedState(dismissEnabled)
    val dismiss = {
        if (currentEnabled && !state.isClosing) {
            if (currentConfirm()) scope.launch { state.hide(); currentDismiss() }
            else state.restore()
        }
        Unit
    }
    SideEffect {
        state.enabled = { currentEnabled }
        state.requestDismiss = dismiss
    }
    LaunchedEffect(state) { state.open() }
    DisposableEffect(parent, state) {
        val previous = parent?.covering
        parent?.let { it.children++; it.covering = state }
        onDispose {
            parent?.let {
                it.children--
                if (it.covering === state) it.covering = previous
            }
        }
    }
    val base = MaterialTheme.colorScheme
    val colors = if (LocalAloginDarkTheme.current && parent == null) base.copy(
        background = base.surfaceContainer,
        surface = base.surfaceContainer,
        surfaceContainer = base.surfaceContainerHigh,
        surfaceContainerHigh = base.surfaceContainerHighest
    ) else base

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false
    )) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setDimAmount(0f)
                setWindowAnimations(0)
            }
        }
        MaterialTheme(colorScheme = colors) {
            CompositionLocalProvider(LocalSheet provides state) {
                BoxWithConstraints(Modifier.fillMaxSize().testTag("sheet-host")) {
                    val density = LocalDensity.current
                    val topInset = WindowInsets.safeDrawing.getTop(density)
                    val keyboard = imeInsets.getBottom(density)
                    val navigation = WindowInsets.navigationBars.getBottom(density)
                    val available = (constraints.maxHeight - topInset).coerceAtLeast(1)
                    val room = (available - keyboard - with(density) { 14.dp.toPx() }).coerceAtLeast(1f)
                    val short = minOf(available * SheetShortDetent, room)
                    val tall = minOf(available * SheetTallDetent, room)
                    SideEffect {
                        state.available = available.toFloat().coerceAtLeast(1f)
                        state.short = short / state.available
                        state.tall = tall / state.available
                        state.rubberExtent = with(density) { 100.dp.toPx() }
                    }
                    val shown = (state.position * available).coerceIn(0f, room)
                    val height = max(shown, short)
                    val progress = (shown / short).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxSize().background(base.scrim.copy(alpha = 0.32f * progress))
                        .testTag("sheet-scrim")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = dismiss))
                    val cover = state.covering?.let { (it.position / it.short).coerceIn(0f, 1f) } ?: 0f
                    val dragModifier = Modifier.draggable(
                        rememberDraggableState { state.dragBy(-it, stretch = true) },
                        Orientation.Vertical, enabled = dismissEnabled,
                        onDragStarted = { state.beginDrag() },
                        onDragStopped = { state.endDrag(-it) }
                    )
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = with(density) { keyboard.toDp() })
                            .fillMaxWidth().widthIn(max = 840.dp)
                            .heightIn(min = with(density) { short.toDp() }, max = with(density) { height.toDp() })
                            .onSizeChanged { state.measuredHeight = it.height.toFloat() }
                            .graphicsLayer {
                                translationY = (short - shown).coerceAtLeast(0f) - 14.dp.toPx() * cover
                                scaleX = 1f - 0.0835f * cover
                                scaleY = scaleX
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            }
                            .semantics {
                                paneTitle = title
                                if (dismissEnabled) {
                                    dismiss { dismiss(); true }
                                    expand { state.expand(); true }
                                    collapse { state.collapse(); true }
                                }
                            }.testTag("app-sheet"),
                        shape = AppSheetShape,
                        color = colors.background
                    ) {
                        Box {
                            Column {
                                Box(Modifier.weight(1f, fill = false).fillMaxWidth()
                                    .then(dragModifier)
                                    .consumeWindowInsets(imeInsets)
                                    .layout { measurable, constraints ->
                                        val body = measurable.measure(constraints)
                                        state.contentLimit = if (body.height < constraints.maxHeight) {
                                            max(body.height.toFloat(), short)
                                        } else Float.POSITIVE_INFINITY
                                        layout(body.width, body.height) { body.place(0, 0) }
                                    }) {
                                    val padding = PaddingValues(start = ScreenHorizontalPadding,
                                        top = SheetHeaderHeight + SheetContentTopPadding, end = ScreenHorizontalPadding,
                                        bottom = 20.dp + with(density) { if (keyboard > 0) 0.dp else navigation.toDp() })
                                    content(padding)
                                }
                            }
                            SheetHeader(title, dismiss, dismissEnabled, dragModifier, state.headerActions ?: actions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, onClose: () -> Unit, enabled: Boolean, dragModifier: Modifier,
    actions: @Composable RowScope.() -> Unit) {
    val color = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(SheetHeaderHeight)
        .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.96f), color.copy(alpha = 0.9f), Color.Transparent)))) {
        // Keep button drags out of the sheet's resize gesture.
        Box(Modifier.matchParentSize().then(dragModifier))
        Column {
            Box(Modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.BottomCenter) {
                Spacer(Modifier.size(28.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
            Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = ScreenHorizontalPadding)) {
                Text(title, Modifier.align(Alignment.Center).padding(horizontal = 60.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = NavigationTitleTextStyle.fontSize),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                NavigationBackButton(onClick = onClose, enabled = enabled,
                    modifier = Modifier.align(Alignment.CenterStart),
                    contentDescription = stringResource(R.string.sheet_close))
                Row(Modifier.align(Alignment.CenterEnd), content = actions)
            }
        }
    }
}

/** Capture whether the gesture began at the top before a child consumes any distance. */
fun Modifier.sheetScrollable(canScrollBackward: () -> Boolean): Modifier = composed {
    val state = LocalSheet.current ?: return@composed this
    val currentCanScroll by rememberUpdatedState(canScrollBackward)
    val connection = remember(state) {
        object : NestedScrollConnection {
            var startedAtTop = false
            var first = true
            var held = false
            fun reset() { first = true; held = false }
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (first) { startedAtTop = !currentCanScroll(); first = false }
                val take = if (available.y < 0 && (held || !currentCanScroll()) ||
                    available.y > 0 && startedAtTop && !currentCanScroll()) {
                    -state.dragBy(-available.y)
                } else 0f
                if (take != 0f) held = true
                return Offset(0f, take)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                val consume = held && (available.y >= 0 || state.position < state.tall - 0.001f)
                state.endDrag(-available.y)
                reset()
                return if (consume) available else Velocity.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                state.endDrag(0f)
                reset()
                return Velocity.Zero
            }
        }
    }
    this.pointerInput(connection) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            connection.reset()
            do { val event = awaitPointerEvent(PointerEventPass.Final) }
            while (event.changes.any { it.pressed })
        }
    }.nestedScroll(connection)
}
