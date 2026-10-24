package top.steins.autologin.ui.component

import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PasswordRevealStateTest {
    @Test
    fun typingCharacter_revealsOnlyLatestCharacterForTwoSeconds() = runTest {
        val state = PasswordRevealState(this)
        state.onPasswordChanged("", "a", enabled = true)
        advanceTimeBy(1_000)
        state.onPasswordChanged("a", "ab", enabled = true)
        assertEquals("•b", transformed("ab", state))

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals("•b", transformed("ab", state))
        advanceTimeBy(1)
        runCurrent()
        assertEquals("••", transformed("ab", state))
    }

    @Test
    fun deletingOrPastingMultipleCharacters_keepsAllCharactersHidden() = runTest {
        val state = PasswordRevealState(this)
        state.onPasswordChanged("a", "ab", enabled = true)
        state.onPasswordChanged("ab", "a", enabled = true)
        assertNull(state.revealedRange)
        state.onPasswordChanged("a", "abc", enabled = true)
        assertEquals("•••", transformed("abc", state))
    }

    @Test
    fun replacingOrInsertingCharacter_revealsOnlyTheChangedCharacter() = runTest {
        val state = PasswordRevealState(this)
        state.onPasswordChanged("abcd", "abxd", enabled = true)
        assertEquals("••x•", transformed("abxd", state))
        state.onPasswordChanged("abxd", "abyxd", enabled = true)
        assertEquals("••y••", transformed("abyxd", state))
        state.hide()
    }

    @Test
    fun unicodeCharacter_isRevealedWithoutBreakingCursorOffsets() = runTest {
        val state = PasswordRevealState(this)
        state.onPasswordChanged("a", "a😀", enabled = true)
        val result = PasswordRevealTransformation(state.revealedRange)
            .filter(AnnotatedString("a😀"))
        assertEquals("•😀", result.text.text)
        assertEquals(3, result.offsetMapping.originalToTransformed(3))
        state.hide()
    }

    @Test
    fun hidingOrDisablingReveal_cancelsPendingExposure() = runTest {
        val state = PasswordRevealState(this)
        state.onPasswordChanged("", "a", enabled = true)
        state.hide()
        assertNull(state.revealedRange)
        state.onPasswordChanged("a", "ab", enabled = false)
        assertEquals("••", transformed("ab", state))
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(state.revealedRange)
    }

    private fun transformed(text: String, state: PasswordRevealState): String =
        PasswordRevealTransformation(state.revealedRange).filter(AnnotatedString(text)).text.text
}
