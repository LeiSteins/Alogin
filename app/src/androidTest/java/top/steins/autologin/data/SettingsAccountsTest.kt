package top.steins.autologin.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SettingsAccountsTest {
    // 为测试偏好文件单独加前缀，避免覆盖已安装应用中的真实账号。
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("accounts_test_$name", mode)
    }
    private val settings get() = context.getSharedPreferences("alogin_settings", Context.MODE_PRIVATE)
    private val secure get() = context.getSharedPreferences("alogin_secure", Context.MODE_PRIVATE)

    @Before
    @After
    fun clearTestPreferences() {
        settings.edit().clear().commit()
        secure.edit().clear().commit()
    }

    @Test
    fun encryptedLegacyAccount_migratesAndMultipleAccountsSurviveRestart() {
        val cipher = CredentialCipher(context)
        val encryptedUser = requireNotNull(cipher.encrypt("username", "alice"))
        val encryptedPassword = requireNotNull(cipher.encrypt("password", "legacy-secret"))
        assertTrue(secure.edit().putString("username", encryptedUser)
            .putString("password", encryptedPassword).commit())
        val repository = SettingsRepository(context)
        assertEquals("alice", repository.username.value)
        assertEquals("legacy-secret", repository.password.value)
        assertFalse(secure.contains("username"))
        assertEquals(CredentialSaveResult.SAVED, repository.saveCredentials("bob", "second-secret"))
        assertEquals(CredentialSaveResult.SAVED, repository.selectAccount("alice"))

        val restored = SettingsRepository(context)
        assertEquals(2, restored.accounts.value.size)
        assertEquals("legacy-secret", restored.password.value)
        val ciphertext = secure.getString("saved_accounts_v1", "")!!
        assertFalse(ciphertext.contains("alice"))
        assertFalse(ciphertext.contains("secret"))
        assertEquals(CredentialSaveResult.SAVED, restored.removeAccount("alice"))
        assertEquals("bob", restored.username.value)
        assertEquals("second-secret", restored.password.value)
        restored.removeAccount("bob")
        val empty = SettingsRepository(context)
        assertTrue(empty.accounts.value.isEmpty())
        assertEquals("", empty.username.value)
        assertEquals("", empty.password.value)
    }

    @Test
    fun plaintextLegacyAccount_migratesAndInvalidSavePreservesCredentials() {
        settings.edit().putString("username", "alice").putString("password", "secret").commit()
        val repository = SettingsRepository(context)
        assertEquals("alice", repository.accounts.value.single().username)
        assertFalse(settings.contains("username"))
        assertFalse(settings.contains("password"))
        assertEquals(CredentialSaveResult.INVALID_INPUT, repository.saveCredentials("", ""))
        assertEquals(CredentialSaveResult.INVALID_INPUT, repository.selectAccount("missing"))
        assertEquals("secret", SettingsRepository(context).password.value)
    }
}
