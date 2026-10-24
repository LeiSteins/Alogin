package top.steins.autologin.ui.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PasswordRevealState(private val scope: CoroutineScope) {
    var revealedRange: IntRange? by mutableStateOf(null)
        private set

    private var hideJob: Job? = null

    fun onPasswordChanged(previous: String, current: String, enabled: Boolean) {
        hide()
        if (!enabled) return

        revealedRange = findInsertedCharacter(previous, current)
        if (revealedRange != null) {
            hideJob = scope.launch {
                delay(2_000)
                revealedRange = null
                hideJob = null
            }
        }
    }

    fun hide() {
        hideJob?.cancel()
        hideJob = null
        revealedRange = null
    }

    // 按完整 Unicode 字符比较，删除和多字符粘贴时不展示已有密码。
    private fun findInsertedCharacter(previous: String, current: String): IntRange? {
        var start = 0
        while (start < previous.length && start < current.length) {
            val character = current.codePointAt(start)
            if (previous.codePointAt(start) != character) break
            start += Character.charCount(character)
        }
        var previousEnd = previous.length
        var currentEnd = current.length
        while (previousEnd > start && currentEnd > start) {
            val character = current.codePointBefore(currentEnd)
            if (previous.codePointBefore(previousEnd) != character) break
            previousEnd -= Character.charCount(character)
            currentEnd -= Character.charCount(character)
        }
        return if (current.codePointCount(start, currentEnd) == 1) {
            start until currentEnd
        } else {
            null
        }
    }
}

internal class PasswordRevealTransformation(
    private val revealedRange: IntRange?
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val masked = text.text.mapIndexed { index, character ->
            if (revealedRange?.contains(index) == true) character else '\u2022'
        }.joinToString("")
        return TransformedText(AnnotatedString(masked), OffsetMapping.Identity)
    }
}
