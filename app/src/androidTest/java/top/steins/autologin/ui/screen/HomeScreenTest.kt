package top.steins.autologin.ui.screen

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.R
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.DeviceLogoutResult
import top.steins.autologin.ui.theme.AloginTheme

class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loggedInTitle = context.getString(R.string.home_logged_in)
    private val networkTitle = context.getString(R.string.home_current_network)
    private val overview = AccountOverview(
        username = "alice",
        usedFlowMb = "1024",
        remainingFlowMb = "2048",
        remainingMoneyYuan = "20.00",
        devices = emptyList()
    )

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

    private fun showHome(state: MutableState<HomeState>, onRetry: () -> Unit = {}) {
        compose.setContent {
            AloginTheme(darkTheme = false) {
                HomeScreen(
                    wifiName = "Campus WiFi",
                    ipAddress = "10.0.0.1",
                    isOnline = state.value.isOnline,
                    isLoggingIn = false,
                    accountOverview = state.value.overview,
                    isAccountInfoLoading = state.value.isLoading,
                    accountInfoError = state.value.error,
                    isDeviceListAvailable = false,
                    canLogoutDevices = false,
                    networkStatusError = "",
                    targetWifis = listOf("Campus WiFi"),
                    onRefreshAccountInfo = {},
                    onCheckNetworkStatus = {},
                    onRetryAccountInfo = onRetry,
                    onLogin = {},
                    onLogoutDevice = { _, _, _ -> DeviceLogoutResult.Success },
                    onRefreshAfterDeviceLogout = {},
                    onRefreshAfterIndeterminateDeviceLogout = {},
                    onShowToast = {},
                    onNavigateToAccount = {},
                    onNavigateToSettings = {}
                )
            }
        }
    }

    private data class HomeState(
        val isOnline: Boolean = false,
        val isLoading: Boolean = false,
        val overview: AccountOverview? = null,
        val error: String = ""
    )
}
