package top.steins.autologin.ui.screen

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.R
import top.steins.autologin.domain.model.AccountDevice
import top.steins.autologin.ui.theme.AloginTheme

class HomeDeviceComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private val firstDevice = AccountDevice("first", "00:11:22:33:44:55", "10.0.0.1")
    private val secondDevice = AccountDevice("second", "00:11:22:33:44:66", "10.0.0.2")

    @Test
    fun openingList_displaysExistingRowsWithoutReplayingRowAnimation() {
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                AloginTheme(darkTheme = false) {
                    DeviceListCard(
                        devices = listOf(firstDevice, secondDevice),
                        localIpAddress = firstDevice.ipAddress,
                        enabled = true,
                        onLogoutDevice = {}
                    )
                }
            }
            compose.onNodeWithText(firstDevice.macAddress).assertIsDisplayed()
            compose.onNodeWithText(secondDevice.macAddress).assertIsDisplayed()
            val initialTop = secondRowTop()
            compose.mainClock.advanceTimeBy(1_000)
            assertEquals(initialTop, secondRowTop(), 0.01f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun refreshingDevices_shrinksRemovedRowBeforeShowingEmptyState() {
        val devices = mutableStateOf(listOf(firstDevice, secondDevice))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val removeDeviceDescription = context.getString(R.string.logout_device)
        val emptyMessage = context.getString(R.string.home_no_devices)
        compose.setContent {
            AloginTheme(darkTheme = false) {
                DeviceListCard(
                    devices = devices.value,
                    localIpAddress = firstDevice.ipAddress,
                    enabled = true,
                    onLogoutDevice = {}
                )
            }
        }
        val initialTop = secondRowTop()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { devices.value = listOf(secondDevice) }
            compose.mainClock.advanceTimeBy(125)
            val intermediateTop = secondRowTop()
            assertTrue(intermediateTop < initialTop)
            compose.onAllNodesWithContentDescription(removeDeviceDescription).onFirst()
                .assertIsNotEnabled()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(firstDevice.macAddress).assertDoesNotExist()
            assertTrue(secondRowTop() < intermediateTop)

            compose.runOnIdle { devices.value = emptyList() }
            compose.mainClock.advanceTimeBy(125)
            compose.onNodeWithText(emptyMessage).assertDoesNotExist()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(secondDevice.macAddress).assertDoesNotExist()
            compose.onNodeWithText(emptyMessage).assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun refreshDuringExit_restoresSessionAndDisplaysUpdatedDetailsAndNewDevice() {
        val devices = mutableStateOf(listOf(firstDevice))
        compose.setContent {
            AloginTheme(darkTheme = false) {
                DeviceListCard(
                    devices = devices.value,
                    localIpAddress = firstDevice.ipAddress,
                    enabled = true,
                    onLogoutDevice = {}
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { devices.value = emptyList() }
            compose.mainClock.advanceTimeBy(125)
            val updatedDevice = firstDevice.copy(ipAddress = "10.0.0.3")
            compose.runOnIdle { devices.value = listOf(updatedDevice, secondDevice) }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(firstDevice.macAddress).assertIsDisplayed()
            compose.onNodeWithText(secondDevice.macAddress).assertIsDisplayed()
            compose.onNodeWithText(updatedDevice.ipAddress, substring = true).assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun secondRowTop(): Float = compose.onNodeWithText(secondDevice.macAddress)
        .fetchSemanticsNode().positionInRoot.y
}
