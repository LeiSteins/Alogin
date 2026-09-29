package top.steins.autologin.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.network.update.UpdateInfo
import top.steins.autologin.network.update.UpdateDownloadState
import top.steins.autologin.network.update.UpdateDownloadFailure
import androidx.compose.runtime.mutableStateOf
import top.steins.autologin.ui.theme.AloginTheme

class UpdateDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun downloadStates_showProgressRetryAndInstallActions() {
        val state = mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Downloading(42))
        var actions = 0
        composeRule.setContent {
            AloginTheme {
                UpdateDialog(
                    update = UpdateInfo("0.2.0", "alogin-v0.2.0.apk", "", 20),
                    download = state.value,
                    onDownload = { actions++ },
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithText("已下载 42%").assertIsDisplayed()
        composeRule.onNodeWithText("正在下载…").assertIsNotEnabled()
        composeRule.runOnIdle { state.value = UpdateDownloadState.Failed(UpdateDownloadFailure.MISSING_FILE) }
        composeRule.onNodeWithText("更新包已丢失，请重新下载").assertIsDisplayed()
        composeRule.onNodeWithText("重新下载").performClick()
        composeRule.runOnIdle { state.value = UpdateDownloadState.Ready }
        composeRule.onNodeWithText("安装").performClick()
        composeRule.runOnIdle { assertEquals(2, actions) }
    }

    @Test
    fun longReleaseNotes_canBeReadAndDoNotHideActions() {
        var downloadCount = 0
        var dismissCount = 0
        val notes = (1..80).joinToString("\n") { "- 第 $it 项更新：改进登录体验" }
        composeRule.setContent {
            AloginTheme {
                UpdateDialog(
                    update = UpdateInfo(
                        version = "0.2.0",
                        fileName = "alogin-v0.2.0.apk",
                        downloadUrl = "https://aloginupdate.steins.top/alogin-v0.2.0.apk",
                        versionCode = 20,
                        releaseNotes = notes
                    ),
                    onDownload = { downloadCount++ },
                    onDismiss = { dismissCount++ }
                )
            }
        }
        composeRule.onNodeWithText("发现新版本 0.2.0").assertIsDisplayed()
        composeRule.onNodeWithText(notes).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("下载").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("稍后").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, downloadCount)
            assertEquals(1, dismissCount)
        }
    }
}
