package top.steins.autologin.ui.component

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer

val DismissEasing = CubicBezierEasing(0f, 0f, 0.4f, 1f)
val AppearEasing = CubicBezierEasing(0.6f, 0f, 1f, 1f)

/**
 * 缩放 + 淡入淡出动画容器。
 * 内容在出现和消失的整个过程中保持布局，退出结束后才移除，
 * 以避免动画被布局变化打断或留下空白占位。
 *
 * @param visible true 时以缩放+淡入出现，false 时以缩小+淡出消失
 * @param initiallyVisible 是否在首次组合时直接呈现当前可见状态，用于恢复已有页面状态
 * @param durationMillis 动画时长
 * @param enterDelayMillis 出现动画开始前的延迟
 * @param exitDelayMillis 消失动画开始前的延迟
 * @param dismissScale 消失时的目标缩放比例
 * @param dismissAlpha 消失时的目标透明度
 * @param transformOrigin 缩放变换原点，默认左中
 */
@Composable
fun ScaleFadeBox(
    visible: Boolean,
    modifier: Modifier = Modifier,
    initiallyVisible: Boolean = false,
    durationMillis: Int = 250,
    enterDelayMillis: Int = 0,
    exitDelayMillis: Int = 0,
    dismissScale: Float = 0.88f,
    dismissAlpha: Float = 0f,
    transformOrigin: TransformOrigin = TransformOrigin(0f, 0.5f),
    content: @Composable BoxScope.() -> Unit
) {
    // 保留调用方给出的初始呈现状态，避免将组合重建误判为一次新入场。
    val transitionState = remember {
        MutableTransitionState(initialState = visible && initiallyVisible)
    }
    transitionState.targetState = visible
    val transition = rememberTransition(transitionState, label = "scaleFade")

    val scale by transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = durationMillis,
                delayMillis = if (targetState) enterDelayMillis else exitDelayMillis,
                easing = if (targetState) AppearEasing else DismissEasing
            )
        },
        label = "scale"
    ) { if (it) 1f else dismissScale }

    val alpha by transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis = durationMillis,
                delayMillis = if (targetState) enterDelayMillis else exitDelayMillis,
                easing = if (targetState) AppearEasing else DismissEasing
            )
        },
        label = "alpha"
    ) { if (it) 1f else dismissAlpha }

    // 退出结束后再移除内容，既能完整播放消失动画，也不会留下空白布局。
    if (transition.currentState || transition.targetState || transition.isRunning) {
        Box(
            modifier = modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                this.transformOrigin = transformOrigin
            }
        ) {
            content()
        }
    }
}
