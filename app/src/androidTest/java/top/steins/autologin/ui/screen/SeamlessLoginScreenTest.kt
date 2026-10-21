package top.steins.autologin.ui.screen

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.SeamlessUiState
import top.steins.autologin.domain.model.BoundDevice
import top.steins.autologin.domain.model.BoundDeviceList
import top.steins.autologin.domain.model.SeamlessAction
import top.steins.autologin.domain.model.SeamlessInfo
import top.steins.autologin.domain.model.SeamlessStatus
import top.steins.autologin.ui.theme.AloginTheme

class SeamlessLoginScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun state(count: Int = 3) = SeamlessUiState(info = SeamlessInfo(
        username = "student", sessionGeneration = 1, status = SeamlessStatus.Enabled,
        deviceList = BoundDeviceList(count, (1..count).map {
            BoundDevice("00:11:22:33:44:0$it", it == 1, "Android", "2026-10-20 09:00:00", "10.1.2.$it")
        })
    ))

    @Test
    fun deviceList_placesOnlineStatusBesideMacAndMarksOnlyOnlineLocalDevice() {
        val screenState = mutableStateOf(state())
        compose.setContent {
            AloginTheme(darkTheme = false) {
                SeamlessLoginScreen(screenState.value, {}, {}, {}, localIpAddress = "10.1.2.1")
            }
        }
        compose.onAllNodesWithText("本机").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("解除绑定").assertCountEquals(3)
        compose.onNodeWithText("解除绑定").assertDoesNotExist()
        val macBounds = compose.onNodeWithText("00:11:22:33:44:01").fetchSemanticsNode().boundsInRoot
        val statusBounds = compose.onNodeWithText("在线").fetchSemanticsNode().boundsInRoot
        assertTrue(statusBounds.left >= macBounds.right)
        assertTrue(statusBounds.top >= macBounds.top && statusBounds.bottom <= macBounds.bottom)
        capture("seamless-device-list-light")

        compose.runOnIdle {
            val info = screenState.value.info!!
            val devices = info.deviceList!!.devices.map { it.copy(isOnline = false) }
            screenState.value = screenState.value.copy(info = info.copy(deviceList = BoundDeviceList(3, devices)))
        }
        compose.onNodeWithText("本机").assertDoesNotExist()
    }

    @Test
    fun deleteIcon_requiresConfirmationBeforeUnbindingSelectedDevice() {
        var submitted: SeamlessAction? = null
        compose.setContent {
            AloginTheme(darkTheme = true) {
                SeamlessLoginScreen(state(), {}, { submitted = it }, {}, localIpAddress = "10.1.2.1")
            }
        }
        capture("seamless-device-list-dark")
        compose.onAllNodesWithContentDescription("解除绑定").onFirst().performClick()
        compose.onNodeWithText("解除设备绑定？").assertIsDisplayed()
        compose.runOnIdle { assertNull(submitted) }
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals(SeamlessAction.Unbind("00:11:22:33:44:01"), submitted) }
    }

    @Test
    fun fullList_keepsAddEnabledAndRequiresReplacementConfirmation() {
        var submitted: SeamlessAction? = null
        compose.setContent {
            AloginTheme(darkTheme = false) {
                SeamlessLoginScreen(state(), {}, { submitted = it }, {})
            }
        }
        compose.onNodeWithText("已绑定 3 / 3 台").assertExists()
        compose.onNodeWithText("添加设备").assertIsEnabled().performClick()
        compose.onNodeWithText("MAC 地址").performTextInput("aa:bb:cc:11:22:33")
        compose.onNodeWithText("绑定设备").performClick()
        compose.onNodeWithText("替换已有设备？").assertIsDisplayed()
        compose.runOnIdle { assertNull(submitted) }
        capture("seamless-replacement-confirm", dialog = true)
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle {
            assertEquals(SeamlessAction.Bind("AABBCC112233", allowReplacement = true), submitted)
        }
    }

    @Test
    fun disabling_requiresConfirmationAndWarnsAboutDisconnect() {
        var submitted: SeamlessAction? = null
        compose.setContent {
            AloginTheme(darkTheme = false) { SeamlessLoginScreen(state(1), {}, { submitted = it }, {}) }
        }
        capture("seamless-management-light")
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("关闭无感知可能导致设备下线，是否继续？").assertIsDisplayed()
        compose.runOnIdle { assertNull(submitted) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNull(submitted) }
    }

    @Test
    fun pendingOperation_disablesAdditionalWritesAndRefresh() {
        compose.setContent {
            AloginTheme(darkTheme = true) { SeamlessLoginScreen(state().copy(isSubmitting = true), {}, {}, {}) }
        }
        compose.onNodeWithText("添加设备").assertIsNotEnabled()
        compose.onNodeWithText("刷新").assertIsNotEnabled()
        compose.onNode(isToggleable()).assertIsNotEnabled()
        capture("seamless-management-dark")
    }

    @Test
    fun missingList_doesNotExposeAnEmptyQuotaOrAllowBinding() {
        compose.setContent {
            AloginTheme(darkTheme = false) {
                SeamlessLoginScreen(SeamlessUiState(info = SeamlessInfo(
                    "student", 1, SeamlessStatus.Enabled, devicesError = "列表查询失败"
                )), {}, {}, {})
            }
        }
        compose.onNodeWithText("添加设备").assertDoesNotExist()
        compose.onNodeWithText("已绑定 0 / 3 台").assertDoesNotExist()
        compose.onNodeWithText("列表查询失败").assertIsDisplayed()
    }

    @Test
    fun initialQuery_showsSwitchCardWithoutProgressOrDeviceCards() {
        val screenState = mutableStateOf(SeamlessUiState(isLoading = true))
        compose.setContent {
            AloginTheme(darkTheme = false) { SeamlessLoginScreen(screenState.value, {}, {}, {}) }
        }
        compose.onNode(isToggleable()).assertIsDisplayed().assertIsNotEnabled()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
        compose.onNodeWithText("添加设备").assertDoesNotExist()
        capture("seamless-initial-query")

        compose.runOnIdle { screenState.value = state(1) }
        compose.onNodeWithText("已绑定 1 / 3 台").assertIsDisplayed()
        compose.onNodeWithText("00:11:22:33:44:01").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsEnabled()
    }

    @Test
    fun refreshing_keepsPreviousStatusAndDeviceCardsUntilNewDataArrives() {
        val screenState = mutableStateOf(state(1))
        compose.setContent {
            AloginTheme(darkTheme = false) { SeamlessLoginScreen(screenState.value, {}, {}, {}) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { screenState.value = screenState.value.copy(isLoading = true) }
            compose.mainClock.advanceTimeBy(125)
            compose.onNodeWithText("00:11:22:33:44:01").assertExists()
            compose.onNode(isToggleable()).assertIsDisplayed().assertIsEnabled()
            compose.onNode(isToggleable()).assertIsOn()
            compose.onNodeWithText("已开启").assertIsDisplayed()
            compose.onNodeWithText("正在查询无感知登录状态…").assertDoesNotExist()

            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("00:11:22:33:44:01").assertIsDisplayed()
            compose.onNodeWithText("添加设备").assertIsDisplayed().assertIsEnabled()
            compose.onNode(isToggleable()).assertIsDisplayed()

            val nextInfo = state(1).info!!
            compose.runOnIdle {
                screenState.value = SeamlessUiState(info = nextInfo.copy(status = SeamlessStatus.Disabled, deviceList = BoundDeviceList(1,
                    listOf(nextInfo.deviceList!!.devices.single().copy(macAddress = "00:11:22:33:44:09")))))
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("00:11:22:33:44:09").assertIsDisplayed()
            compose.onNodeWithText("00:11:22:33:44:01").assertDoesNotExist()
            compose.onNodeWithText("已关闭").assertIsDisplayed()
            compose.onNode(isToggleable()).assertIsOff().assertIsEnabled()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun reentryWithCachedData_keepsActionEntriesEnabledButBlocksSubmissionWhileLoading() {
        var submitted: SeamlessAction? = null
        compose.setContent {
            AloginTheme(darkTheme = false) {
                SeamlessLoginScreen(state(1).copy(isLoading = true), {}, { submitted = it }, {})
            }
        }
        compose.onNodeWithText("已开启").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn().assertIsEnabled()
        compose.onNodeWithText("已绑定 1 / 3 台").assertIsDisplayed()
        compose.onNodeWithText("00:11:22:33:44:01").assertIsDisplayed()
        compose.onNodeWithText("添加设备").assertIsEnabled()
        capture("seamless-cached-refresh")

        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("确认").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("添加设备").performClick()
        compose.onNodeWithText("MAC 地址").performTextInput("aa:bb:cc:11:22:33")
        compose.onNodeWithText("绑定设备").assertIsNotEnabled()
        compose.runOnIdle { assertNull(submitted) }
    }

    @Test
    fun unboundDevice_remainsUntilFadeOutFinishesWhileOtherCardsStayVisible() {
        val screenState = mutableStateOf(state(2))
        compose.setContent {
            AloginTheme(darkTheme = false) { SeamlessLoginScreen(screenState.value, {}, {}, {}) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { screenState.value = state(1) }
            compose.mainClock.advanceTimeBy(125)
            compose.onNodeWithText("00:11:22:33:44:02").assertExists()
            compose.onNodeWithText("已绑定 1 / 3 台").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("00:11:22:33:44:02").assertDoesNotExist()
            compose.onNodeWithText("00:11:22:33:44:01").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outputDirectory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let { File(it).apply { mkdirs() } }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        File(outputDirectory, "$name.png").outputStream().use {
            val node = if (dialog) compose.onAllNodes(isDialog()).onLast() else compose.onRoot()
            node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
