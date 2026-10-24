package top.steins.autologin.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.steins.autologin.ui.theme.LocalAloginDarkTheme

class CapsuleToastState(
    private val scope: CoroutineScope
) {
    var message by mutableStateOf("")
        private set
    var visible by mutableStateOf(false)
        private set

    private var hideJob: Job? = null

    fun show(text: String, durationMs: Long = 2000) {
        hideJob?.cancel()
        message = text
        visible = true
        hideJob = scope.launch {
            delay(durationMs)
            visible = false
        }
    }
}

@Composable
fun rememberCapsuleToastState(scope: CoroutineScope = rememberCoroutineScope()): CapsuleToastState {
    return remember(scope) { CapsuleToastState(scope) }
}

@Composable
fun CapsuleToast(
    state: CapsuleToastState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = 120),
            initialOffsetY = { -it }
        ) + fadeIn(animationSpec = tween(durationMillis = 90)),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .statusBarsPadding()
                .padding(top = 14.dp, start = 24.dp, end = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shadowElevation = if (LocalAloginDarkTheme.current) 6.dp else 0.dp,
        ) {
            Text(
                text = state.message,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
