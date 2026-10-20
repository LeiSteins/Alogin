package top.steins.autologin.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import top.steins.autologin.domain.model.CredentialSaveResult
import top.steins.autologin.domain.model.SavedAccount

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
    fun encryptedLegacyAccount_migratesAndMultipleAccountsSurviveRestart() = runBlocking {
        val cipher = CredentialCipher(context)
        val encryptedUser = requireNotNull(cipher.encrypt("username", "alice"))
        val encryptedPassword = requireNotNull(cipher.encrypt("password", "legacy-secret"))
        assertTrue(secure.edit().putString("username", encryptedUser)
            .putString("password", encryptedPassword).commit())
        val repository = SettingsRepository(context)
        assertTrue(repository.accounts.value.isEmpty())
        repository.initialize()
        assertEquals("alice", repository.selectedAccountUsername.value)
        assertEquals("legacy-secret", repository.password.value)
        assertFalse(secure.contains("username"))
        assertEquals(CredentialSaveResult.SAVED, repository.saveCredentials("bob", "second-secret"))
        assertEquals(CredentialSaveResult.SAVED, repository.selectAccount("alice"))

        val restored = SettingsRepository(context)
        restored.initialize()
        assertEquals(2, restored.accounts.value.size)
        assertEquals("legacy-secret", restored.password.value)
        val ciphertext = secure.getString("saved_accounts_v1", "")!!
        assertFalse(ciphertext.contains("alice"))
        assertFalse(ciphertext.contains("secret"))
        assertEquals(CredentialSaveResult.SAVED, restored.removeAccount("alice"))
        assertEquals("bob", restored.selectedAccountUsername.value)
        assertEquals("second-secret", restored.password.value)
        restored.removeAccount("bob")
        val empty = SettingsRepository(context)
        empty.initialize()
        assertTrue(empty.accounts.value.isEmpty())
        assertEquals("", empty.selectedAccountUsername.value)
        assertEquals("", empty.password.value)
    }

    @Test
    fun plaintextLegacyAccount_migratesAndInvalidSavePreservesCredentials() = runBlocking {
        settings.edit().putString("username", "alice").putString("password", "secret").commit()
        val repository = SettingsRepository(context)
        repository.initialize()
        assertEquals("alice", repository.accounts.value.single().username)
        assertFalse(settings.contains("username"))
        assertFalse(settings.contains("password"))
        assertEquals(CredentialSaveResult.INVALID_INPUT, repository.saveCredentials("", ""))
        assertEquals(CredentialSaveResult.INVALID_INPUT, repository.selectAccount("missing"))
        assertEquals("secret", SettingsRepository(context).also { it.initialize() }.password.value)
    }

    @Test
    fun failedSaveAndAccountChanges_preservePersistedAndPublishedAccounts() = runBlocking {
        var failWrites = false
        val repository = SettingsRepository(writeControlledContext { failWrites })
        assertEquals(CredentialSaveResult.SAVED, repository.saveCredentials("alice", "first"))
        assertEquals(CredentialSaveResult.SAVED, repository.saveCredentials("bob", "second"))
        val ciphertext = secure.getString("saved_accounts_v1", null)
        failWrites = true

        assertEquals(CredentialSaveResult.STORAGE_UNAVAILABLE, repository.saveCredentials("bob", "changed"))
        assertEquals(CredentialSaveResult.STORAGE_UNAVAILABLE, repository.selectAccount("alice"))
        assertEquals(CredentialSaveResult.STORAGE_UNAVAILABLE, repository.removeAccount("bob"))
        assertEquals("bob", repository.selectedAccountUsername.value)
        assertEquals("second", repository.password.value)
        assertEquals(SavedAccount("bob", "second"), repository.readActiveAccount())
        assertEquals(ciphertext, secure.getString("saved_accounts_v1", null))
        assertEquals("second", SettingsRepository(context).also { it.initialize() }.password.value)

        failWrites = false
        assertEquals(CredentialSaveResult.SAVED, repository.selectAccount("alice"))
        assertEquals("first", repository.password.value)
    }

    @Test
    fun failedMigration_keepsLegacyCredentialsAndCanBeRetried() = runBlocking {
        settings.edit().putString("username", "alice").putString("password", "legacy").commit()
        var failWrites = true
        val repository = SettingsRepository(writeControlledContext { failWrites })
        repository.initialize()
        assertEquals("alice", repository.selectedAccountUsername.value)
        assertEquals("legacy", settings.getString("password", null))
        assertFalse(secure.contains("saved_accounts_v1"))

        failWrites = false
        assertEquals(CredentialSaveResult.SAVED, repository.saveCredentials("alice", "updated"))
        assertFalse(settings.contains("username"))
        assertFalse(settings.contains("password"))
        assertEquals("updated", SettingsRepository(context).also { it.initialize() }.password.value)
    }

    @Test
    fun accountWritesCalledOnMain_runOnIoAndConcurrentSavesAreSerialized() = runBlocking {
        val repository = SettingsRepository(writeControlledContext { false })
        withContext(Dispatchers.Main) {
            repository.initialize()
            assertEquals(CredentialSaveResult.SAVED, repository.saveCredentials("alice", "first"))
        }
        coroutineScope {
            val bob = async { repository.saveCredentials("bob", "second") }
            val carol = async { repository.saveCredentials("carol", "third") }
            assertEquals(CredentialSaveResult.SAVED, bob.await())
            assertEquals(CredentialSaveResult.SAVED, carol.await())
        }
        val restored = SettingsRepository(context).also { it.initialize() }
        assertEquals(setOf("alice", "bob", "carol"), restored.accounts.value.map { it.username }.toSet())
    }

    private fun writeControlledContext(failWrites: () -> Boolean): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = super.getSharedPreferences(name, mode)
            if (name != "alogin_secure") return preferences
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        private var writesAccounts = false
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                            if (key == "saved_accounts_v1") writesAccounts = true
                            editor.putString(key, value)
                            return this
                        }

                        override fun commit(): Boolean {
                            if (writesAccounts) {
                                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                                if (failWrites()) return false
                            }
                            return editor.commit()
                        }
                    }
                }
            }
        }
    }
}
