package top.steins.autologin.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.ui.theme.AloginTheme
import java.io.File

class AppSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun draggingBackButtonKeepsSheetStillWhileHeaderDragStillExpandsIt() {
        val visible = mutableStateOf(true)
        var dismissals = 0
        lateinit var state: AppSheetState
        compose.setContent {
            AloginTheme(darkTheme = false) {
                if (visible.value) {
                    state = rememberAppSheetState()
                    AppSheet("测试面板", { dismissals++; visible.value = false }, state = state) { padding ->
                        val scroll = rememberScrollState()
                        Column(Modifier.fillMaxWidth().sheetScrollable { scroll.canScrollBackward }
                            .verticalScroll(scroll).padding(padding)) {
                            repeat(40) { Text("Row $it", Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }
        val back = compose.onNodeWithContentDescription("关闭面板")
        val bounds = back.fetchSemanticsNode().boundsInRoot
        back.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        back.performTouchInput { moveBy(Offset(width * 1.5f, height * 1.5f)) }
        compose.mainClock.advanceTimeBy(240)
        assertEquals(bounds, back.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            assertEquals(SheetShortDetent, state.position, 0.002f)
            assertEquals(0, dismissals)
        }
        back.performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithTag("app-sheet").performTouchInput {
            swipe(start = Offset(center.x, 12f), end = Offset(center.x, -height * 0.5f), durationMillis = 500)
        }
        compose.runOnIdle { assertEquals(SheetTallDetent, state.position, 0.002f) }
        back.performClick()
        compose.onNodeWithTag("app-sheet").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun openingExpandingAndClosingKeepOneScrollableViewport() {
        val visible = mutableStateOf(true)
        lateinit var state: AppSheetState
        compose.setContent {
            AloginTheme(darkTheme = false) {
                if (visible.value) {
                    state = rememberAppSheetState()
                    AppSheet("测试面板", { visible.value = false }, state = state) { padding ->
                        val scroll = rememberScrollState()
                        Column(Modifier.fillMaxWidth().testTag("sheet-list")
                            .sheetScrollable { scroll.canScrollBackward }.verticalScroll(scroll).padding(padding)) {
                            repeat(40) { Text("Row $it", Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val collapsed = compose.onNodeWithTag("app-sheet").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { assertEquals(SheetShortDetent, state.position, 0.002f) }
        capture("sheet-light-collapsed")
        compose.onNodeWithTag("sheet-list").performTouchInput {
            swipe(start = Offset(center.x, height * 0.8f), end = Offset(center.x, height * 0.15f), durationMillis = 600)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(SheetTallDetent, state.position, 0.002f) }
        assertTrue(compose.onNodeWithTag("app-sheet").fetchSemanticsNode().boundsInRoot.height > collapsed)
        capture("sheet-light-expanded")
        compose.onNodeWithTag("sheet-scrim").performTouchInput { click(Offset(10f, 10f)) }
        compose.onNodeWithTag("app-sheet").assertDoesNotExist()
    }

    @Test
    fun aGestureThatScrollsBackToTheTopDoesNotCollapseTheSheet() {
        lateinit var state: AppSheetState
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.setContent {
            AloginTheme(darkTheme = true) {
                state = rememberAppSheetState()
                AppSheet("测试面板", {}, state = state) { padding ->
                    scroll = rememberScrollState()
                    Column(Modifier.fillMaxWidth().testTag("sheet-list")
                        .sheetScrollable { scroll.canScrollBackward }.verticalScroll(scroll).padding(padding)) {
                        repeat(40) { Text("Row $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        compose.runOnIdle { state.expand() }
        compose.waitForIdle()
        compose.onNodeWithTag("sheet-list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(scroll.value > 0) }
        compose.onNodeWithTag("sheet-list").performTouchInput {
            swipe(start = Offset(center.x, height * 0.12f), end = Offset(center.x, height * 0.95f), durationMillis = 600)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(SheetTallDetent, state.position, 0.002f) }
        capture("sheet-dark-expanded")
    }

    @Test
    fun dismissedDirtyFormRestoresTheSheetUntilTheGuardAllowsClosing() {
        val visible = mutableStateOf(true)
        var allow = false
        var requests = 0
        compose.setContent {
            AloginTheme {
                if (visible.value) AppSheet("表单", { visible.value = false },
                    confirmDismiss = { requests++; allow }) { padding -> Text("草稿", Modifier.padding(padding)) }
            }
        }
        compose.onNodeWithContentDescription("关闭面板").performClick()
        compose.onNodeWithText("草稿").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests); allow = true }
        compose.onNodeWithContentDescription("关闭面板").performClick()
        compose.onNodeWithTag("app-sheet").assertDoesNotExist()
    }

    @Test
    fun shortContentStaysAtTheShortDetentWhenExpanded() {
        lateinit var state: AppSheetState
        compose.setContent {
            AloginTheme {
                state = rememberAppSheetState()
                AppSheet("短内容", {}, state = state) { padding -> Text("内容", Modifier.padding(padding)) }
            }
        }
        val initial = compose.onNodeWithTag("app-sheet").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { state.expand() }
        compose.waitForIdle()
        assertEquals(initial, compose.onNodeWithTag("app-sheet").fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test
    fun keyboardInsetsRaiseTheFormWithoutLosingTheDraft() {
        val keyboard = mutableStateOf(0.dp)
        compose.setContent {
            AloginTheme {
                AppSheet("键盘表单", {}, imeInsets = WindowInsets(bottom = keyboard.value)) { padding ->
                    val scroll = rememberScrollState()
                    var value by remember { mutableStateOf("") }
                    Column(Modifier.fillMaxWidth().sheetScrollable { scroll.canScrollBackward }
                        .verticalScroll(scroll).padding(padding)) {
                        OutlinedTextField(value, { value = it }, Modifier.testTag("draft"))
                        repeat(10) { Text("Field $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        val before = compose.onNodeWithTag("app-sheet").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("draft").performClick().performTextInput("draft")
        compose.runOnIdle { keyboard.value = 300.dp }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onNodeWithTag("app-sheet").fetchSemanticsNode().boundsInRoot.top < before - 10f
        }
        compose.onNodeWithTag("draft").assertIsDisplayed().assertTextContains("draft")
        capture("sheet-keyboard")
    }

    @Test
    fun nestedSheetShrinksItsParentAndRestoresItAfterClosing() {
        val child = mutableStateOf(false)
        lateinit var state: AppSheetState
        compose.setContent {
            AloginTheme(darkTheme = true) {
                state = rememberAppSheetState()
                AppSheet("父面板", {}, state = state) { padding ->
                    Text("父内容", Modifier.padding(padding))
                    if (child.value) AppSheet("子面板", { child.value = false }) { inner ->
                        Text("子内容", Modifier.padding(inner))
                    }
                }
            }
        }
        val width = compose.onNodeWithTag("app-sheet").fetchSemanticsNode().boundsInRoot.width
        compose.runOnIdle { child.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, state.children) }
        capture("sheet-dark-stacked")
        compose.onAllNodesWithContentDescription("关闭面板").onLast().performClick()
        compose.runOnIdle { assertEquals(0, state.children) }
        assertEquals(width, compose.onNodeWithTag("app-sheet").fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: context.getExternalFilesDir(null)!!
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            compose.onAllNodesWithTag("sheet-host").onLast().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
