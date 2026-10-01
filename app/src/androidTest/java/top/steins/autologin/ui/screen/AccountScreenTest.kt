package top.steins.autologin.ui.screen

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.data.CredentialSaveResult
import top.steins.autologin.data.SavedAccount
import top.steins.autologin.ui.theme.AloginTheme

class AccountScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun addingAccount_validatesFieldsAndSavesCustomInput() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            AloginTheme(darkTheme = false) {
                AccountScreen("", emptyList(),
                    onSaveCredentials = { user, pass -> saved = user to pass; CredentialSaveResult.SAVED },
                    onSelectAccount = { CredentialSaveResult.SAVED },
                    onRemoveAccount = { CredentialSaveResult.SAVED },
                    onShowToast = {}, onNavigateBack = {})
            }
        }
        capture("account-empty")
        compose.onNodeWithText("添加第一个账号").performClick()
        capture("account-add")
        compose.onNodeWithText("保存并使用").performScrollTo().performClick()
        compose.onNodeWithText("请填写用户名和密码").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithContentDescription("用户名").performTextInput("alice")
        compose.onNodeWithContentDescription("密码").performTextInput("secret")
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertExists()
        compose.onNodeWithText("保存并使用").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("alice" to "secret", saved) }
    }

    @Test
    fun switchingAndDeletingAccount_requireSeparateActionsAndConfirmation() {
        var selected = ""
        var deleted = ""
        compose.setContent {
            AloginTheme(darkTheme = false) {
                AccountScreen("alice", listOf(SavedAccount("alice", "a"), SavedAccount("bob", "b")),
                    onSaveCredentials = { _, _ -> CredentialSaveResult.SAVED },
                    onSelectAccount = { selected = it; CredentialSaveResult.SAVED },
                    onRemoveAccount = { deleted = it; CredentialSaveResult.SAVED },
                    onShowToast = {}, onNavigateBack = {})
            }
        }
        capture("account-list")
        compose.onNodeWithText("bob").performClick()
        compose.runOnIdle { assertEquals("bob", selected) }
        compose.onNodeWithContentDescription("删除账号 bob").performClick()
        compose.runOnIdle { assertEquals("", deleted) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals("", deleted) }
        compose.onNodeWithContentDescription("删除账号 bob").performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("bob", deleted) }
    }
    @Test
    fun editingAccount_inDarkThemeKeepsUsernameAndUpdatesPassword() {
        var saved: Pair<String, String>? = null
        compose.setContent {
            AloginTheme(darkTheme = true) {
                AccountScreen("alice", listOf(SavedAccount("alice", "old")),
                    onSaveCredentials = { user, pass -> saved = user to pass; CredentialSaveResult.SAVED },
                    onSelectAccount = { CredentialSaveResult.SAVED },
                    onRemoveAccount = { CredentialSaveResult.SAVED },
                    onShowToast = {}, onNavigateBack = {})
            }
        }
        capture("account-list-dark")
        compose.onNodeWithContentDescription("编辑账号 alice").performClick()
        capture("account-edit-dark")
        compose.onNodeWithContentDescription("密码").performTextReplacement("new")
        compose.onNodeWithText("保存并使用").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("alice" to "new", saved) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
