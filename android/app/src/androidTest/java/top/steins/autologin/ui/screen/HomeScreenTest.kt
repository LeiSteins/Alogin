package top.steins.autologin.ui.screen

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.R
import top.steins.autologin.domain.model.AccountDevice
import top.steins.autologin.domain.model.AccountOverview
import top.steins.autologin.domain.model.SeamlessStatus
import top.steins.autologin.ui.theme.AloginTheme

class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loggedInTitle = context.getString(R.string.home_logged_in)
    private val networkTitle = context.getString(R.string.home_current_network)
    private val devicesTitle = context.getString(R.string.home_online_devices)
    private val currentDevice = AccountDevice("current", "00:11:22:33:44:55", "10.0.0.1")
    private val otherDevice = AccountDevice("other", "00:11:22:33:44:66", "10.0.0.2")
    private val overview = AccountOverview(
        username = "alice",
        usedTrafficMegabytes = "1024",
        remainingTrafficMegabytes = "2048",
        remainingMoneyYuan = "20.00",
        devices = emptyList()
    )

    @Test
    fun returningWhileDeviceLogoutIsPending_keepsDeviceActionsDisabled() {
        val state = mutableStateOf(
            HomeState(
                isOnline = true,
                overview = overview.copy(devices = listOf(currentDevice)),
                isDeviceListAvailable = true,
                isLoggingOutDevice = true
            )
        )
        var logoutCount = 0
        showHome(state, onLogoutDevice = { logoutCount++ })
        compose.onAllNodesWithContentDescription(context.getString(R.string.logout_device))
            .onFirst().assertIsNotEnabled()

        compose.runOnIdle { state.value = state.value.copy(isLoggingOutDevice = false) }
        compose.onAllNodesWithContentDescription(context.getString(R.string.logout_device))
            .onFirst().performClick()
        compose.runOnIdle { assertEquals(1, logoutCount) }
    }

    @Test
    fun confirmingOnline_showsNetworkSummaryAndWaitsForDetailsBeforeShowingAccountCard() {
        val state = mutableStateOf(HomeState())
        showHome(state)
        compose.onNodeWithText(networkTitle).assertIsDisplayed()

        compose.runOnIdle { state.value = HomeState(isOnline = true, isLoading = true) }
        compose.waitForIdle()
        compose.onNodeWithText(networkTitle).assertDoesNotExist()
        compose.onNodeWithText("Campus WiFi").assertIsDisplayed()
        compose.onNodeWithText("10.0.0.1").assertIsDisplayed()
        compose.onNodeWithText(loggedInTitle).assertDoesNotExist()

        compose.runOnIdle { state.value = HomeState(isOnline = true, overview = overview) }
        compose.onNodeWithText(loggedInTitle).assertIsDisplayed()
        compose.onNodeWithText(overview.username).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_money_yuan, "20.00"))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_flow_remaining, "2G", "3G"))
            .assertIsDisplayed()
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { path ->
            val directory = File(path).apply { mkdirs() }
            File(directory, "home-account-seamless-status.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun detailsArrivingImmediately_waitForNetworkCardToFinishExiting() {
        val state = mutableStateOf(HomeState())
        showHome(state)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.value = HomeState(isOnline = true, overview = overview) }
            compose.mainClock.advanceTimeBy(125)
            compose.onNodeWithText(networkTitle).assertExists()
            compose.onNodeWithText(loggedInTitle).assertDoesNotExist()
            compose.onNodeWithText(overview.username).assertDoesNotExist()

            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(networkTitle).assertDoesNotExist()
            compose.onNodeWithText(loggedInTitle).assertIsDisplayed()
            compose.onNodeWithText(overview.username).assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun detailsFailure_keepsErrorAndRetryAvailableWithoutShowingAnEmptyCardWhileLoading() {
        val state = mutableStateOf(HomeState(isOnline = true, isLoading = true))
        var retryCount = 0
        showHome(state, onRetry = { retryCount++ })
        compose.onNodeWithText(loggedInTitle).assertDoesNotExist()

        compose.runOnIdle {
            state.value = HomeState(isOnline = true, error = "详情获取失败")
        }
        compose.onNodeWithText("详情获取失败").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_retry_fetch)).performClick()
        compose.runOnIdle {
            assertEquals(1, retryCount)
            state.value = HomeState(isOnline = true, isLoading = true)
        }
        compose.onNodeWithText(loggedInTitle).assertDoesNotExist()

        compose.runOnIdle { state.value = HomeState(isOnline = true, overview = overview) }
        compose.onNodeWithText(loggedInTitle).assertIsDisplayed()
        compose.onNodeWithText(overview.username).assertIsDisplayed()
    }

    @Test
    fun goingOffline_keepsAccountDetailsUntilTheExitAnimationFinishes() {
        val state = mutableStateOf(HomeState(isOnline = true, overview = overview))
        showHome(state)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.value = HomeState() }
            compose.mainClock.advanceTimeBy(125)
            compose.onNodeWithText(loggedInTitle).assertExists()
            compose.onNodeWithText(overview.username).assertExists()

            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(loggedInTitle).assertDoesNotExist()
            compose.onNodeWithText(overview.username).assertDoesNotExist()
            compose.onNodeWithText(networkTitle).assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun loggingOutCurrentDevice_waitsForOfflineConfirmationThenRemovesRowBeforeCardsExit() {
        val state = mutableStateOf(
            HomeState(
                isOnline = true,
                overview = overview.copy(devices = listOf(currentDevice, otherDevice)),
                isDeviceListAvailable = true
            )
        )
        var logoutCount = 0
        showHome(state, onLogoutDevice = { logoutCount++ })
        val initialOtherRowTop = compose.onNodeWithText(otherDevice.macAddress)
            .fetchSemanticsNode().positionInRoot.y
        compose.mainClock.autoAdvance = false
        try {
            compose.onAllNodesWithContentDescription(context.getString(R.string.logout_device))
                .onFirst().performClick()
            compose.runOnIdle { assertEquals(1, logoutCount) }
            compose.onNodeWithText(currentDevice.macAddress).assertIsDisplayed()

            // 模拟离线核验清空详情、设备可用标记及当前 IP。
            compose.runOnIdle { state.value = HomeState(ipAddress = "") }
            compose.mainClock.advanceTimeBy(125)
            compose.onNodeWithText(currentDevice.macAddress).assertExists()
            val shrinkingOtherRowTop = compose.onNodeWithText(otherDevice.macAddress)
                .fetchSemanticsNode().positionInRoot.y
            assertTrue(shrinkingOtherRowTop < initialOtherRowTop)
            compose.onNodeWithText(loggedInTitle).assertIsDisplayed()
            compose.onNodeWithText(devicesTitle).assertIsDisplayed()
            compose.onNodeWithText(currentDevice.ipAddress).assertIsDisplayed()
            compose.onNodeWithText(networkTitle).assertDoesNotExist()

            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithText(currentDevice.macAddress).assertDoesNotExist()
            compose.onNodeWithText(otherDevice.macAddress).assertExists()
            compose.onNodeWithText(loggedInTitle).assertExists()
            compose.onNodeWithText(devicesTitle).assertExists()
            compose.onNodeWithText(currentDevice.ipAddress).assertExists()
            compose.onNodeWithText(networkTitle).assertDoesNotExist()

            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(loggedInTitle).assertDoesNotExist()
            compose.onNodeWithText(devicesTitle).assertDoesNotExist()
            compose.onNodeWithText(otherDevice.macAddress).assertDoesNotExist()
            compose.onNodeWithText(currentDevice.ipAddress).assertDoesNotExist()
            compose.onNodeWithText(networkTitle).assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun goingOfflineWithoutCurrentDevice_skipsRowRemovalAndWaitsForOnlineCardsToExit() {
        val state = mutableStateOf(
            HomeState(
                isOnline = true,
                overview = overview.copy(devices = listOf(otherDevice)),
                isDeviceListAvailable = true
            )
        )
        showHome(state)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.value = HomeState() }
            compose.mainClock.advanceTimeBy(125)
            compose.onNodeWithText(loggedInTitle).assertExists()
            compose.onNodeWithText(devicesTitle).assertExists()
            compose.onNodeWithText(otherDevice.macAddress).assertExists()
            compose.onNodeWithText(networkTitle).assertDoesNotExist()

            compose.mainClock.advanceTimeBy(275)
            compose.onNodeWithText(loggedInTitle).assertDoesNotExist()
            compose.onNodeWithText(devicesTitle).assertDoesNotExist()
            compose.onNodeWithText(networkTitle).assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun reconnectingDuringOnlineCardsExit_cancelsOfflineSequenceAndRestoresCurrentDevice() {
        val onlineState = HomeState(
            isOnline = true,
            overview = overview.copy(devices = listOf(currentDevice, otherDevice)),
            isDeviceListAvailable = true
        )
        val state = mutableStateOf(onlineState)
        showHome(state)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { state.value = HomeState() }
            compose.mainClock.advanceTimeBy(375)
            compose.onNodeWithText(currentDevice.macAddress).assertDoesNotExist()
            compose.onNodeWithText(networkTitle).assertDoesNotExist()

            compose.runOnIdle { state.value = onlineState }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText(currentDevice.macAddress).assertIsDisplayed()
            compose.onNodeWithText(otherDevice.macAddress).assertIsDisplayed()
            compose.onNodeWithText(loggedInTitle).assertIsDisplayed()
            compose.onNodeWithText(devicesTitle).assertIsDisplayed()
            compose.onNodeWithText(networkTitle).assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun showHome(
        state: MutableState<HomeState>,
        onRetry: () -> Unit = {},
        onLogoutDevice: (AccountDevice) -> Unit = {}
    ) {
        compose.setContent {
            AloginTheme(darkTheme = false) {
                HomeScreen(
                    wifiName = "Campus WiFi",
                    ipAddress = state.value.ipAddress,
                    isOnline = state.value.isOnline,
                    isLoggingIn = false,
                    accountOverview = state.value.overview,
                    isAccountInfoLoading = state.value.isLoading,
                    accountInfoError = state.value.error,
                    isDeviceListAvailable = state.value.isDeviceListAvailable,
                    canLogoutDevices = state.value.isDeviceListAvailable,
                    networkStatusError = "",
                    targetSsids = listOf("Campus WiFi"),
                    onRefreshAccountInfo = {},
                    onCheckNetworkStatus = {},
                    onRetryAccountInfo = onRetry,
                    onLogin = {},
                    isLoggingOutDevice = state.value.isLoggingOutDevice,
                    onLogoutDevice = onLogoutDevice,
                    onShowToast = {},
                    onNavigateToAccount = {},
                    onNavigateToSettings = {},
                    seamlessStatus = SeamlessStatus.Enabled
                )
            }
        }
    }

    private data class HomeState(
        val isOnline: Boolean = false,
        val isLoading: Boolean = false,
        val overview: AccountOverview? = null,
        val error: String = "",
        val isDeviceListAvailable: Boolean = false,
        val isLoggingOutDevice: Boolean = false,
        val ipAddress: String = "10.0.0.1"
    )
}
