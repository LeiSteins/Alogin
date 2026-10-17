package top.steins.autologin.ui.screen

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.R
import top.steins.autologin.ui.theme.AloginTheme

class WifiConfigScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun openingPage_displaysExistingWifisWithoutAdvancingAnimationClock() {
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                AloginTheme(darkTheme = false) {
                    WifiConfigScreen(
                        targetWifis = listOf("Campus-A", "Campus-B"),
                        onAddTargetWifi = {},
                        onRemoveTargetWifi = {},
                        onShowToast = {},
                        onNavigateBack = {}
                    )
                }
            }
            compose.onNodeWithText("Campus-A").assertIsDisplayed()
            compose.onNodeWithText("Campus-B").assertIsDisplayed()
            val initialTop = compose.onNodeWithText("Campus-B").fetchSemanticsNode().positionInRoot.y

            compose.mainClock.advanceTimeBy(1_000)
            val finalTop = compose.onNodeWithText("Campus-B").fetchSemanticsNode().positionInRoot.y
            assertEquals(initialTop, finalTop, 0.01f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun deletingWifi_movesFollowingRowBeforeRemovingDataAndShowsEmptyStateAfterLastDeletion() {
        val wifis = mutableStateOf(listOf("Campus-A", "Campus-B"))
        val removed = mutableListOf<String>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deleteDescription = context.getString(R.string.action_delete)
        val emptyMessage = context.getString(R.string.wifi_empty)
        compose.setContent {
            AloginTheme(darkTheme = false) {
                WifiConfigScreen(
                    targetWifis = wifis.value,
                    onAddTargetWifi = {},
                    onRemoveTargetWifi = { ssid ->
                        removed.add(ssid)
                        wifis.value = wifis.value - ssid
                    },
                    onShowToast = {},
                    onNavigateBack = {}
                )
            }
        }
        compose.waitForIdle()
        val initialTop = compose.onNodeWithText("Campus-B").fetchSemanticsNode().positionInRoot.y

        compose.mainClock.autoAdvance = false
        try {
            val firstDelete = compose.onNode(
                hasContentDescription(deleteDescription) and hasAnySibling(hasText("Campus-A"))
            )
            firstDelete.performClick()
            compose.mainClock.advanceTimeBy(125)
            firstDelete.assertIsNotEnabled()
            val intermediateTop = compose.onNodeWithText("Campus-B")
                .fetchSemanticsNode().positionInRoot.y
            assertTrue(intermediateTop < initialTop)
            compose.runOnIdle {
                assertTrue(removed.isEmpty())
                assertEquals(listOf("Campus-A", "Campus-B"), wifis.value)
            }

            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("Campus-A").assertDoesNotExist()
            compose.onNodeWithText("Campus-B").assertIsDisplayed()
            val finalTop = compose.onNodeWithText("Campus-B").fetchSemanticsNode().positionInRoot.y
            assertTrue(finalTop < intermediateTop)
            compose.runOnIdle { assertEquals(listOf("Campus-A"), removed) }

            compose.onNode(hasContentDescription(deleteDescription)).performClick()
            compose.mainClock.advanceTimeBy(125)
            compose.onNodeWithText(emptyMessage).assertDoesNotExist()
            compose.runOnIdle { assertEquals(listOf("Campus-A"), removed) }

            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("Campus-B").assertDoesNotExist()
            compose.onNodeWithText(emptyMessage).assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(listOf("Campus-A", "Campus-B"), removed)
                assertTrue(wifis.value.isEmpty())
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
