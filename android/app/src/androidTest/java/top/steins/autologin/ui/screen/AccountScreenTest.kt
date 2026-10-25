package top.steins.autologin.ui.screen

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.steins.autologin.domain.model.CredentialSaveResult
import top.steins.autologin.domain.model.SavedAccount
import top.steins.autologin.ui.theme.AloginTheme

class AccountScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun asynchronousSave_disablesRepeatedSubmissionAndKeepsEditorOnStorageFailure() {
        var gate = CompletableDeferred<CredentialSaveResult>()
        var saveCount = 0
        var message = ""
        compose.setContent {
            AloginTheme(darkTheme = false) {
                AccountScreen("", emptyList(),
                    onSaveCredentials = { _, _ -> saveCount++; gate.await() },
                    onSelectAccount = { CredentialSaveResult.SAVED },
                    onRemoveAccount = { CredentialSaveResult.SAVED },
                    onShowToast = { message = it }, onNavigateBack = {})
            }
        }
        compose.onNodeWithText("添加账号").performClick()
        compose.onNodeWithContentDescription("用户名").performTextInput("alice")
        compose.onNodeWithContentDescription("密码").performTextInput("secret")
        compose.onNodeWithText("保存并使用").performScrollTo().performClick()
        compose.onNodeWithText("保存并使用").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, saveCount)
            gate.complete(CredentialSaveResult.STORAGE_UNAVAILABLE)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("用户名").assertExists()
        compose.onNodeWithText("保存并使用").assertIsEnabled()
        compose.runOnIdle {
            assertEquals("无法写入账号信息，请检查存储空间后重试", message)
            gate = CompletableDeferred(CredentialSaveResult.SAVED)
        }
        compose.onNodeWithText("保存并使用").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("用户名").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, saveCount) }
    }

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
        compose.onNodeWithText("添加账号").performClick()
        compose.onNodeWithText("删除账号").assertDoesNotExist()
        capture("account-add")
        compose.onNodeWithText("保存并使用").performScrollTo().performClick()
        compose.onNodeWithText("请填写用户名和密码").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithContentDescription("用户名").performTextInput("alice")
        compose.onNodeWithContentDescription("密码").performTextInput("secret")
        compose.onNodeWithContentDescription("密码")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertExists()
        compose.onNodeWithContentDescription("隐藏密码").performClick()
        compose.onNodeWithContentDescription("显示密码").assertExists()
        compose.onNodeWithText("保存并使用").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("alice" to "secret", saved) }
    }

    @Test
    fun savingAccount_keepsEditorUntilExitAnimationCompletes() {
        val accounts = mutableStateOf(emptyList<SavedAccount>())
        var saveCount = 0
        compose.setContent {
            AloginTheme(darkTheme = false) {
                AccountScreen("", accounts.value,
                    onSaveCredentials = { user, pass ->
                        saveCount++
                        accounts.value = listOf(SavedAccount(user, pass))
                        CredentialSaveResult.SAVED
                    },
                    onSelectAccount = { CredentialSaveResult.SAVED },
                    onRemoveAccount = { CredentialSaveResult.SAVED },
                    onShowToast = {}, onNavigateBack = {})
            }
        }
        compose.onNodeWithText("添加账号").performClick()
        compose.onNodeWithContentDescription("用户名").performTextInput("alice")
        compose.onNodeWithContentDescription("密码").performTextInput("secret")
        compose.onNodeWithText("保存并使用").performScrollTo()
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("保存并使用").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("保存并使用").assertIsNotEnabled()
            compose.onNodeWithContentDescription("用户名").assertExists()
            compose.onNodeWithText("此账号已保存，请返回列表编辑密码或直接切换。").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(1, saveCount)
                assertEquals(listOf(SavedAccount("alice", "secret")), accounts.value)
            }

            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithText("保存并使用").assertDoesNotExist()
            compose.onNodeWithContentDescription("用户名").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
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
        compose.onNodeWithContentDescription("删除账号 bob").assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑账号 bob").performClick()
        compose.onNodeWithContentDescription("密码").performTextReplacement("updated")
        capture("account-edit-delete")
        compose.onNodeWithContentDescription("删除账号 bob").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", deleted) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals("", deleted) }
        compose.onNodeWithContentDescription("密码").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("updated"))
        )
        compose.onNodeWithContentDescription("删除账号 bob").performScrollTo().performClick()
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("bob", deleted) }
        compose.onNodeWithContentDescription("密码").assertDoesNotExist()
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
        compose.onNodeWithContentDescription("密码")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("密码").performTextReplacement("new")
        compose.onNodeWithContentDescription("密码")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("显示密码").assertDoesNotExist()
        compose.onNodeWithContentDescription("隐藏密码").assertDoesNotExist()
        compose.onNodeWithText("保存并使用").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { saved != null }
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
