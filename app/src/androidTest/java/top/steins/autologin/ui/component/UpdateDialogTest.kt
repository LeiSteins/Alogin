package top.steins.autologin.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.network.update.UpdateInfo
import top.steins.autologin.ui.theme.AloginTheme

class UpdateDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

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
