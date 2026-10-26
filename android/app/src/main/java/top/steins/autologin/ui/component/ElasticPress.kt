package top.steins.autologin.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val PressSpring = spring<Float>(dampingRatio = 0.593f, stiffness = 311f)
private val PullSpring = spring<Offset>(dampingRatio = 1f, stiffness = 2742f)
private val ReleaseSpring = spring<Offset>(dampingRatio = 0.593f, stiffness = 311f)

@Composable
internal fun Modifier.elasticPress(
    interactions: InteractionSource,
    enabled: Boolean,
    shape: Shape = RectangleShape,
    animatePress: Boolean = true
): Modifier = this then ElasticPressElement(
    interactions, enabled, shape, MaterialTheme.colorScheme.primary, animatePress
)

private data class ElasticPressElement(
    val interactions: InteractionSource,
    val enabled: Boolean,
    val shape: Shape,
    val focusColor: Color,
    val animatePress: Boolean
) : ModifierNodeElement<ElasticPressNode>() {
    override fun create() = ElasticPressNode(interactions, enabled, shape, focusColor, animatePress)

    override fun update(node: ElasticPressNode) {
        node.shape = shape
        node.focusColor = focusColor
        if (node.interactions !== interactions || node.enabled != enabled || node.animatePress != animatePress) {
            node.interactions = interactions
            node.enabled = enabled
            node.animatePress = animatePress
            node.observeInteractions()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "elasticPress"
        properties["enabled"] = enabled
    }
}

private class ElasticPressNode(
    var interactions: InteractionSource,
    var enabled: Boolean,
    var shape: Shape,
    var focusColor: Color,
    var animatePress: Boolean
) : Modifier.Node(), DrawModifierNode, PointerInputModifierNode {
    private var lift = AnimationState(0f)
    private val pull = Animatable(Offset.Zero, Offset.VectorConverter)
    private var focused by mutableStateOf(false)
    private var observer: Job? = null
    private var swelling: Job? = null
    private var pulling: Job? = null
    private var press: PressInteraction.Press? = null
    private var pointer: PointerId? = null
    private var pointerPressActive = false
    private var pointerCancelled = false
    private var origin = Offset.Zero
    private var displacement = Offset.Zero
    private var pullLimit = 0f

    override fun onAttach() = observeInteractions()

    fun observeInteractions() {
        observer?.cancel()
        swelling?.cancel()
        pulling?.cancel()
        press = null
        pointer = null
        pointerPressActive = false
        pointerCancelled = false
        displacement = Offset.Zero
        focused = false
        lift = AnimationState(0f)
        if (!isAttached) return
        observer = coroutineScope.launch {
            pull.snapTo(Offset.Zero)
            if (!enabled) return@launch
            interactions.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        if (!animatePress || (pointer != null && pointerCancelled)) return@collect
                        press = interaction
                        if (pointer == null) swell()
                    }
                    is PressInteraction.Release -> if (interaction.press === press) {
                        press = null
                        if (!pointerPressActive) release(completeSwell = true)
                    }
                    is PressInteraction.Cancel -> if (interaction.press === press) {
                        press = null
                        // Leaving a click target cancels its action, but the finger still drives the paint.
                        if (!pointerPressActive) release(completeSwell = false)
                    }
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                }
            }
        }
    }

    private fun swell() {
        swelling?.cancel()
        swelling = coroutineScope.launch { lift.animateTo(1f, PressSpring) }
    }

    private fun release(completeSwell: Boolean) {
        press = null
        pointerPressActive = false
        displacement = Offset.Zero
        swelling?.cancel()
        pulling?.cancel()
        pulling = coroutineScope.launch { pull.animateTo(Offset.Zero, ReleaseSpring) }
        swelling = coroutineScope.launch {
            // A quick tap still reaches the same swell as a held press.
            if (completeSwell && lift.value < 0.975f) {
                lift.animateTo(1f, PressSpring, sequentialAnimation = true) {
                    if (value >= 0.975f) cancelAnimation()
                }
            }
            lift.animateTo(0f, PressSpring, sequentialAnimation = true)
        }
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (!enabled || !animatePress) return
        if (pass == PointerEventPass.Final) {
            val change = pointerEvent.changes.firstOrNull { it.id == pointer } ?: return
            if (
                change.pressed && change.previousPressed && change.isConsumed &&
                change.position != change.previousPosition
            ) {
                // A scroll or another gesture owning movement ends the visual press too.
                pointerCancelled = true
                if (pointerPressActive || press != null) release(completeSwell = false)
            }
            return
        }
        if (pass != PointerEventPass.Initial) return
        if (pointer == null) {
            val down = pointerEvent.changes.firstOrNull { it.pressed && !it.previousPressed } ?: return
            pointer = down.id
            pointerCancelled = false
            origin = down.position
            displacement = Offset.Zero
            pullLimit = min(bounds.width, bounds.height) * 7f / 32f
            pointerPressActive = true
            swell()
        }
        val change = pointerEvent.changes.firstOrNull { it.id == pointer } ?: return
        if (!change.pressed) {
            pointer = null
            if (pointerPressActive) release(completeSwell = true)
            return
        }
        displacement = change.position - origin
        if (pointerPressActive) followPointer()
        // Observe only; clickable and scrollable retain ownership of the gesture.
    }

    private fun followPointer() {
        val target = Offset(rubberBand(displacement.x), rubberBand(displacement.y))
        if (target == pull.targetValue) return
        pulling?.cancel()
        pulling = coroutineScope.launch { pull.animateTo(target, PullSpring) }
    }

    private fun rubberBand(distance: Float): Float = if (pullLimit <= 0f) 0f else
        pullLimit * (1f - 1f / (abs(distance) * 0.55f / pullLimit + 1f)) * sign(distance)

    override fun onCancelPointerInput() {
        pointer = null
        pointerCancelled = true
        if (pointerPressActive || press != null) release(completeSwell = false)
    }

    override fun ContentDrawScope.draw() {
        if (size.minDimension <= 0f) return
        val growth = min(0.25f, 16.dp.toPx() / size.maxDimension)
        val swell = if (enabled) 1f + lift.value * growth else 1f
        val offset = if (enabled) pull.value else Offset.Zero
        // Draw transforms keep layout, hit targets, and popup anchors at rest.
        withTransform({
            translate(offset.x, offset.y)
            scale(
                swell * (1f + abs(offset.x) / size.width * 0.5f),
                swell * (1f + abs(offset.y) / size.height * 0.5f),
                center
            )
        }) {
            this@draw.drawContent()
            if (enabled && focused) {
                drawOutline(shape.createOutline(size, layoutDirection, this), focusColor, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
