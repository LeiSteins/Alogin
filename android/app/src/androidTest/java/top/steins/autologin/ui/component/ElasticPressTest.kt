package top.steins.autologin.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.ui.screen.FloatingActionBox
import top.steins.autologin.ui.theme.AloginTheme

@OptIn(ExperimentalTestApi::class)
class ElasticPressTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun heldPressAndDrag_transformPaintingWithoutMovingLayout_thenReturnToRest() {
        var clicks = 0
        showPaintTarget { clicks++ }
        val target = composeRule.onNodeWithTag("target")
        val layout = target.fetchSemanticsNode().boundsInRoot
        val rest = paintedBounds()

        target.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(200)
        val pressed = paintedBounds()
        assertTrue(pressed.width > rest.width)
        assertEquals(layout, target.fetchSemanticsNode().boundsInRoot)

        target.performTouchInput { moveBy(Offset(width * 0.25f, 0f)) }
        composeRule.mainClock.advanceTimeBy(240)
        val dragged = paintedBounds()
        assertTrue(dragged.left + dragged.right > pressed.left + pressed.right)
        assertEquals(layout, target.fetchSemanticsNode().boundsInRoot)

        target.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest, paintedBounds())
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun quickTap_completesSwellAndInvokesClickOnce() {
        var clicks = 0
        showPaintTarget { clicks++ }
        val rest = paintedBounds()
        composeRule.onNodeWithTag("target").performTouchInput {
            down(center)
            advanceEventTime(16)
            up()
        }
        composeRule.mainClock.advanceTimeBy(128)
        assertTrue(paintedBounds().width > rest.width)
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest, paintedBounds())
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun draggingAcrossScreen_keepsFollowingAfterLeavingButtonAndDoesNotClickOnReturn() {
        var clicks = 0
        showPaintTarget { clicks++ }
        val target = composeRule.onNodeWithTag("target")
        val layout = target.fetchSemanticsNode().boundsInRoot
        val farCorner = farCornerFrom("target")
        val rest = paintedBounds()
        target.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(200)
        val pressed = paintedBounds()

        target.performTouchInput { moveTo(farCorner) }
        composeRule.mainClock.advanceTimeBy(240)
        val below = paintedBounds()
        assertTrue(below.width > rest.width)
        assertTrue(below.left + below.right > pressed.left + pressed.right)
        assertTrue(below.top + below.bottom > pressed.top + pressed.bottom)

        target.performTouchInput { moveTo(Offset(farCorner.x, -height.toFloat())) }
        composeRule.mainClock.advanceTimeBy(240)
        val above = paintedBounds()
        assertTrue(above.top + above.bottom < pressed.top + pressed.bottom)
        assertEquals(layout, target.fetchSemanticsNode().boundsInRoot)

        target.performTouchInput { moveTo(center) }
        composeRule.mainClock.advanceTimeBy(240)
        assertTrue(paintedBounds().width > rest.width)
        target.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest, paintedBounds())
        composeRule.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun pointerCancellationAfterLeavingButton_returnsToRestWithoutClick() {
        var clicks = 0
        showPaintTarget { clicks++ }
        val target = composeRule.onNodeWithTag("target")
        val farCorner = farCornerFrom("target")
        val rest = paintedBounds()
        target.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(200)
        target.performTouchInput { moveTo(farCorner) }
        composeRule.mainClock.advanceTimeBy(240)
        assertTrue(paintedBounds().width > rest.width)
        target.performTouchInput { cancel() }
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest, paintedBounds())
        composeRule.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun disablingDuringPress_clearsTransformAndDoesNotClick() {
        val enabled = mutableStateOf(true)
        var clicks = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AloginTheme {
                Box(Modifier.size(240.dp).testTag("canvas"), contentAlignment = Alignment.Center) {
                    PaintTarget(enabled = enabled.value) { clicks++ }
                }
            }
        }
        val rest = paintedBounds()
        composeRule.onNodeWithTag("target").performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.onNodeWithTag("target").performTouchInput { moveBy(Offset(width * 1.2f, 0f)) }
        composeRule.mainClock.advanceTimeBy(240)
        assertTrue(paintedBounds().width > rest.width)
        composeRule.runOnIdle { enabled.value = false }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(rest, paintedBounds())
        composeRule.onNodeWithTag("target").performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun draggingInScrollableList_scrollsAndCancelsClick() {
        var clicks = 0
        var scrollPosition = { 0 }
        composeRule.setContent {
            AloginTheme {
                val scroll = rememberScrollState()
                scrollPosition = { scroll.value }
                Column(Modifier.size(240.dp).testTag("list").verticalScroll(scroll)) {
                    repeat(20) {
                        ElasticButton(
                            onClick = { clicks++ },
                            modifier = Modifier.fillMaxWidth().height(64.dp)
                        ) {
                            Text("Row $it")
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("list").performTouchInput { swipeUp() }
        composeRule.runOnIdle {
            assertTrue(scrollPosition() > 0)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun scrollTakingOverHeldPress_stopsVisualDragUntilPointerUp() {
        var clicks = 0
        var scrollPosition = { 0 }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AloginTheme {
                val scroll = rememberScrollState()
                scrollPosition = { scroll.value }
                Column(Modifier.size(240.dp).testTag("canvas").verticalScroll(scroll)) {
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        PaintTarget { clicks++ }
                    }
                    Spacer(Modifier.height(600.dp))
                }
            }
        }
        val target = composeRule.onNodeWithTag("target")
        val rest = paintedBounds()
        target.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(300)
        assertTrue(paintedBounds().width > rest.width)
        target.performTouchInput { moveBy(Offset(0f, -height * 0.4f)) }
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest.width, paintedBounds().width)
        composeRule.runOnIdle { assertTrue(scrollPosition() > 0) }
        target.performTouchInput { moveBy(Offset(0f, height * 0.1f)) }
        composeRule.mainClock.advanceTimeBy(240)
        assertEquals(rest.width, paintedBounds().width)
        target.performTouchInput { up() }
        composeRule.runOnIdle { assertEquals(0, clicks) }
    }

    @Test
    fun immediateHorizontalDragInScrollableList_startsWithoutWaitingForClickPress() {
        var clicks = 0
        var scrollPosition = { 0 }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AloginTheme {
                val scroll = rememberScrollState()
                scrollPosition = { scroll.value }
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.size(240.dp).testTag("canvas").verticalScroll(scroll)) {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            PaintTarget { clicks++ }
                        }
                        Spacer(Modifier.height(600.dp))
                    }
                }
            }
        }
        val target = composeRule.onNodeWithTag("target")
        val farCorner = farCornerFrom("target")
        val rest = paintedBounds()
        target.performTouchInput {
            down(center)
            advanceEventTime(16)
            moveTo(Offset(farCorner.x, center.y))
        }
        composeRule.mainClock.advanceTimeBy(300)
        val dragged = paintedBounds()
        assertTrue(dragged.width > rest.width)
        assertTrue(dragged.left + dragged.right > rest.left + rest.right)
        target.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest, paintedBounds())
        composeRule.runOnIdle {
            assertEquals(0, scrollPosition())
            assertEquals(0, clicks)
        }
    }

    @Test
    fun nestedIconButton_keepsClickOwnershipAndSupportsKeyboardAndAccessibility() {
        var parentClicks = 0
        var childClicks = 0
        lateinit var inputModeManager: InputModeManager
        composeRule.setContent {
            AloginTheme {
                inputModeManager = LocalInputModeManager.current
                Box(Modifier.size(160.dp).clickable { parentClicks++ }) {
                    ElasticIconButton(onClick = { childClicks++ }, modifier = Modifier.testTag("child")) {
                        Text("+")
                    }
                }
            }
        }
        val child = composeRule.onNodeWithTag("child")
        child.performTouchInput { down(center); up() }
        child.performClick()
        composeRule.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        child.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        child.assertIsFocused()
        child.performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        composeRule.runOnIdle {
            assertEquals(0, parentClicks)
            assertEquals(3, childClicks)
        }
    }

    @Test
    fun iconButtonGroup_movesWholeCapsuleAndKeepsSeparateClickAndFocusTargets() {
        var settingsClicks = 0
        var accountClicks = 0
        lateinit var inputModeManager: InputModeManager
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AloginTheme {
                inputModeManager = LocalInputModeManager.current
                Box(Modifier.size(240.dp).testTag("canvas"), contentAlignment = Alignment.Center) {
                    ElasticIconButtonGroup(
                        modifier = Modifier.testTag("group"),
                        containerColor = Color.Red
                    ) {
                        ElasticIconButton(
                            onClick = { settingsClicks++ },
                            modifier = Modifier.size(44.dp).testTag("settings")
                        ) { Text("S") }
                        ElasticIconButton(
                            onClick = { accountClicks++ },
                            modifier = Modifier.size(44.dp).testTag("account")
                        ) { Text("A") }
                    }
                }
            }
        }
        val settings = composeRule.onNodeWithTag("settings")
        val account = composeRule.onNodeWithTag("account")
        val group = composeRule.onNodeWithTag("group")
        val layout = group.fetchSemanticsNode().boundsInRoot
        assertEquals(2f, layout.width / layout.height, 0.01f)
        val rest = paintedBounds()
        settings.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(200)
        val pressed = paintedBounds()
        assertTrue(pressed.left < rest.left && pressed.right > rest.right)
        settings.performTouchInput { moveBy(Offset(width * 0.2f, 0f)) }
        composeRule.mainClock.advanceTimeBy(240)
        val dragged = paintedBounds()
        assertTrue(dragged.left + dragged.right > pressed.left + pressed.right)
        assertEquals(layout, group.fetchSemanticsNode().boundsInRoot)
        settings.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest, paintedBounds())
        account.performTouchInput { down(center); up() }
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.runOnIdle { inputModeManager.requestInputMode(InputMode.Keyboard) }
        settings.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.mainClock.advanceTimeByFrame()
        settings.assertIsFocused()
        settings.performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        composeRule.mainClock.advanceTimeBy(1500)
        account.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.mainClock.advanceTimeByFrame()
        account.assertIsFocused()
        account.performClick()
        composeRule.runOnIdle {
            assertEquals(2, settingsClicks)
            assertEquals(2, accountClicks)
        }
    }

    @Test
    fun iconButtonGroup_draggingAcrossButtonsAndScreen_keepsCapsuleMovingWithoutClick() {
        var clicks = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AloginTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(240.dp).testTag("canvas"), contentAlignment = Alignment.Center) {
                        ElasticIconButtonGroup(containerColor = Color.Red) {
                            ElasticIconButton(
                                onClick = { clicks++ },
                                modifier = Modifier.size(44.dp).testTag("settings")
                            ) { Text("S") }
                            ElasticIconButton(
                                onClick = { clicks++ },
                                modifier = Modifier.size(44.dp).testTag("account")
                            ) { Text("A") }
                        }
                    }
                }
            }
        }
        val settings = composeRule.onNodeWithTag("settings")
        val farCorner = farCornerFrom("settings")
        val rest = paintedBounds()
        settings.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(200)
        settings.performTouchInput { moveTo(Offset(width * 1.5f, center.y)) }
        composeRule.mainClock.advanceTimeBy(240)
        assertTrue(paintedBounds().width > rest.width)
        settings.performTouchInput { moveTo(farCorner) }
        composeRule.mainClock.advanceTimeBy(240)
        val dragged = paintedBounds()
        assertTrue(dragged.width > rest.width)
        assertTrue(dragged.top + dragged.bottom > rest.top + rest.bottom)
        settings.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest, paintedBounds())
        composeRule.runOnIdle { assertEquals(0, clicks) }
        composeRule.onNodeWithTag("account").performTouchInput { down(center); up() }
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun homeRefreshButton_tapSwellsAndDragReturnsToRestWithoutMovingClickTarget() {
        var clicks = 0
        showFloatingActionTarget { clicks++ }
        val target = composeRule.onNodeWithTag("target")
        val layout = target.fetchSemanticsNode().boundsInRoot
        val rest = paintedBounds()

        target.performTouchInput { down(center); advanceEventTime(16); up() }
        composeRule.mainClock.advanceTimeBy(128)
        assertTrue(paintedBounds().width > rest.width)
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest, paintedBounds())
        composeRule.runOnIdle { assertEquals(1, clicks) }

        target.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(200)
        val pressed = paintedBounds()
        target.performTouchInput { moveBy(Offset(width * 0.25f, 0f)) }
        composeRule.mainClock.advanceTimeBy(240)
        val dragged = paintedBounds()
        assertTrue(dragged.left + dragged.right > pressed.left + pressed.right)
        assertEquals(layout, target.fetchSemanticsNode().boundsInRoot)
        target.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest, paintedBounds())
        composeRule.runOnIdle { assertEquals(2, clicks) }

        val farCorner = farCornerFrom("target")
        target.performTouchInput { down(center) }
        target.performTouchInput { moveTo(farCorner) }
        composeRule.mainClock.advanceTimeBy(240)
        assertTrue(paintedBounds().width > rest.width)
        target.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1500)
        assertEquals(rest, paintedBounds())
        composeRule.runOnIdle { assertEquals(2, clicks) }
    }

    @Test
    fun homeRefreshButton_loadingDuringDrag_clearsTransformAndPreventsClick() {
        var clicks = 0
        val loading = mutableStateOf(false)
        showFloatingActionTarget(isLoading = { loading.value }) { clicks++ }
        val target = composeRule.onNodeWithTag("target")
        val rest = paintedBounds()
        target.performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeBy(200)
        target.performTouchInput { moveBy(Offset(width * 0.25f, 0f)) }
        composeRule.mainClock.advanceTimeBy(240)
        assertTrue(paintedBounds().width > rest.width)
        composeRule.runOnIdle { loading.value = true }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(rest, paintedBounds())
        target.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.runOnIdle { assertEquals(0, clicks) }
    }

    private fun showFloatingActionTarget(isLoading: () -> Boolean = { false }, onClick: () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AloginTheme {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color.Red)) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.size(240.dp).testTag("canvas"), contentAlignment = Alignment.Center) {
                            FloatingActionBox(
                                label = "刷新",
                                isLoading = isLoading(),
                                enabled = !isLoading(),
                                onClick = onClick,
                                modifier = Modifier.size(140.dp, 56.dp).testTag("target")
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showPaintTarget(onClick: () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AloginTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(240.dp).testTag("canvas"), contentAlignment = Alignment.Center) {
                        PaintTarget(onClick = onClick)
                    }
                }
            }
        }
    }

    @Composable
    private fun PaintTarget(enabled: Boolean = true, onClick: () -> Unit) {
        ElasticButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(100.dp, 48.dp).testTag("target"),
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Red,
                disabledContainerColor = Color.Red
            )
        ) {}
    }

    private fun farCornerFrom(tag: String): Offset {
        val screen = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val target = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Offset(screen.right - target.left - 1f, screen.bottom - target.top - 1f)
    }

    private fun paintedBounds(): IntRect {
        val pixels = composeRule.onNodeWithTag("canvas").captureToImage().toPixelMap()
        var left = pixels.width
        var top = pixels.height
        var right = 0
        var bottom = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.red > 0.9f && color.green < 0.1f && color.blue < 0.1f) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x + 1)
                    bottom = maxOf(bottom, y + 1)
                }
            }
        }
        assertTrue("The painted target must be visible", right > left && bottom > top)
        return IntRect(left, top, right, bottom)
    }
}
