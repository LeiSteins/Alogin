package top.steins.autologin.ui.component

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

internal const val SheetShortDetent = 9f / 16f
internal const val SheetTallDetent = 0.9f

internal fun sheetDestination(height: Float, velocity: Float, short: Float, tall: Float): Float {
    val projected = height + velocity * 0.099f
    return listOf(0f, short, tall).minBy { abs(it - projected) }
}

@Stable
class AppSheetState internal constructor(private val scope: CoroutineScope) {
    internal var position by mutableFloatStateOf(0f)
        private set
    internal var target by mutableFloatStateOf(SheetShortDetent)
        private set
    internal var isClosing by mutableStateOf(false)
        private set
    internal var available = 1f
    internal var short = SheetShortDetent
    internal var tall = SheetTallDetent
    internal var measuredHeight = 0f
    internal var contentLimit = Float.POSITIVE_INFINITY
    internal var enabled: () -> Boolean = { true }
    internal var requestDismiss: () -> Unit = {}
    internal var children by mutableStateOf(0)
    internal var covering by mutableStateOf<AppSheetState?>(null)
    internal var rubberExtent = 100f
    internal var headerActions by mutableStateOf<(@Composable RowScope.() -> Unit)?>(null)
    private var motion: Job? = null
    private var dragging = false

    internal fun open() = settle(SheetShortDetent)

    internal fun beginDrag() {
        if (!enabled() || isClosing || dragging) return
        motion?.cancel()
        dragging = true
        if (position >= short && measuredHeight > 0f) {
            position = minOf(position, measuredHeight / available)
        }
    }

    internal fun dragBy(delta: Float, stretch: Boolean = false): Float {
        if (!enabled() || isClosing) return 0f
        if (delta > 0f && !stretch && position >= minOf(tall, contentLimit / available).coerceAtLeast(short)) return 0f
        beginDrag()
        val before = position
        val maximum = minOf(tall, contentLimit / available).coerceAtLeast(short)
        val proposed = position + delta / available
        position = if (stretch && proposed > maximum) {
            val excess = (proposed - maximum) * available
            maximum + (1f - 1f / (excess * 0.55f / rubberExtent + 1f)) * rubberExtent / available
        } else proposed.coerceIn(0f, maximum)
        return (position - before) * available
    }

    internal fun endDrag(velocity: Float) {
        if (!dragging) return
        dragging = false
        val maximum = minOf(tall, contentLimit / available).coerceAtLeast(short)
        val destination = sheetDestination(position, velocity / available, short, maximum)
        if (destination == 0f) {
            requestDismiss()
        } else settle(if (destination == short) SheetShortDetent else SheetTallDetent, velocity / available)
    }

    internal fun restore() = settle(target)

    fun expand() = settle(SheetTallDetent)

    fun collapse() = settle(SheetShortDetent)

    private fun settle(destination: Float, velocity: Float = 0f) {
        if (isClosing) return
        target = destination
        motion?.cancel()
        motion = scope.launch {
            animate(position, destination, initialVelocity = velocity,
                animationSpec = spring(dampingRatio = if (velocity == 0f) 1f else 0.8f,
                    stiffness = 1000f / 3f, visibilityThreshold = 0.001f)) { value, _ ->
                position = value.coerceAtLeast(0f)
            }
        }
    }

    suspend fun hide() {
        if (isClosing) return
        isClosing = true
        dragging = false
        motion?.cancel()
        animate(position, 0f, animationSpec = spring(dampingRatio = 1f,
            stiffness = 1000f / 3f, visibilityThreshold = 0.001f)) { value, _ ->
            position = value.coerceAtLeast(0f)
        }
    }
}

@Composable
fun rememberAppSheetState(): AppSheetState {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppSheetState(scope) }
}
