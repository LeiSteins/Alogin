package top.steins.autologin.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class TargetWifiConfigChangeType {
    ADDED,
    REMOVED
}

data class TargetWifiConfigChange(
    val type: TargetWifiConfigChangeType,
    val ssid: String
)

enum class AppearanceMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * 凭据保存结果。Keystore 和本地加密路径都不可用时不落盘。
 */
enum class CredentialSaveResult {
    SAVED,
    ENCRYPTION_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    INVALID_INPUT
}

/**
 * 设置数据的读取与写入入口，屏蔽 SharedPreferences / Keystore 细节，
 * 使 ViewModel 与界面层不直接依赖存储实现，也便于单元测试注入替身。
 */
interface SettingsGateway {
    /** 在后台完成凭据读取与迁移；认证前必须等待初始化。 */
    suspend fun initialize() = Unit

    val targetSsids: StateFlow<List<String>>
    val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange>
    val accounts: StateFlow<List<SavedAccount>>
    val selectedAccountUsername: StateFlow<String>
    val password: StateFlow<String>
    val appearanceMode: StateFlow<AppearanceMode>
    val autoLoginOnStartup: StateFlow<Boolean>
    val httpLogEnabled: StateFlow<Boolean>
    val credentialResetPending: StateFlow<Boolean>

    fun addAutoDetectedTargetWifi(ssid: String): Boolean

    fun addTargetWifi(ssid: String)

    fun removeTargetWifi(ssid: String)

    fun getWifiLoginMethod(ssid: String): WifiLoginMethod?

    fun saveWifiLoginMethod(ssid: String, method: WifiLoginMethod)

    suspend fun saveCredentials(username: String, password: String): CredentialSaveResult

    suspend fun selectAccount(username: String): CredentialSaveResult

    suspend fun removeAccount(username: String): CredentialSaveResult

    suspend fun readActiveAccount(): SavedAccount?

    fun saveAppearanceMode(mode: AppearanceMode)

    fun setAutoLoginOnStartup(enabled: Boolean)

    fun setHttpLogEnabled(enabled: Boolean)

    fun getLastUpdateCheckAt(): Long

    fun setLastUpdateCheckAt(timestampMillis: Long)

    fun getUpdateCheckRetryAt(): Long

    fun setUpdateCheckRetryAt(timestampMillis: Long)

    fun acknowledgeCredentialReset()
}

class SettingsRepository(context: Context) : SettingsGateway {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(
        "alogin_settings",
        Context.MODE_PRIVATE
    )
    private val securePrefs: SharedPreferences = appContext.getSharedPreferences(
        "alogin_secure",
        Context.MODE_PRIVATE
    )
    private val credentialCipher = CredentialCipher(appContext)

    private val _targetSsids = MutableStateFlow(getTargetSsids())
    override val targetSsids: StateFlow<List<String>> = _targetSsids.asStateFlow()

    // 自动识别发生在当前刷新任务内，无需再次刷新；这里只通知手动配置变更。
    private val _targetWifiConfigChanges = MutableSharedFlow<TargetWifiConfigChange>(
        extraBufferCapacity = 1
    )
    override val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange> =
        _targetWifiConfigChanges.asSharedFlow()

    /**
     * Keystore 密钥永久失效（设备安全设置变化）导致凭据不可恢复时为 true，
     * UI 展示提示后调用 [acknowledgeCredentialReset] 清除。
     */
    private val _credentialResetPending = MutableStateFlow(false)
    override val credentialResetPending: StateFlow<Boolean> = _credentialResetPending.asStateFlow()

    private val accountsMutex = Mutex()
    private var initialized = false
    private var savedAccounts = SavedAccounts()
    private val _accounts = MutableStateFlow(savedAccounts.accounts)
    override val accounts: StateFlow<List<SavedAccount>> = _accounts.asStateFlow()
    private val _selectedAccountUsername = MutableStateFlow(savedAccounts.active?.username.orEmpty())
    override val selectedAccountUsername: StateFlow<String> = _selectedAccountUsername.asStateFlow()
    private val _password = MutableStateFlow(savedAccounts.active?.password.orEmpty())
    override val password: StateFlow<String> = _password.asStateFlow()

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        accountsMutex.withLock { initializeAccountsLocked() }
    }

    private fun initializeAccountsLocked() {
        if (initialized) return
        publishAccounts(readSavedAccounts())
        // 新密文确认写盘后才删除旧凭据；迁移失败保留可重试的旧账号。
        if (!securePrefs.contains(KEY_ACCOUNTS) && savedAccounts.accounts.isNotEmpty()) {
            persistAccounts(savedAccounts)
        }
        initialized = true
    }

    private val _appearanceMode = MutableStateFlow(getAppearanceMode())
    override val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    private val _autoLoginOnStartup = MutableStateFlow(prefs.getBoolean(KEY_AUTO_LOGIN_ON_STARTUP, false))
    override val autoLoginOnStartup: StateFlow<Boolean> = _autoLoginOnStartup.asStateFlow()

    private val _httpLogEnabled = MutableStateFlow(prefs.getBoolean(KEY_HTTP_LOG_ENABLED, false))
    override val httpLogEnabled: StateFlow<Boolean> = _httpLogEnabled.asStateFlow()

    private fun getTargetSsids(): List<String> {
        val serialized = prefs.getString(KEY_TARGET_WIFIS, null) ?: return listOf(DEFAULT_WIFI)
        TargetWifiCodec.decode(serialized)?.let(::normalizeWifiList)?.let { return it }

        // 兼容旧版逗号分隔格式；旧版空字符串仍按默认 WiFi 处理。
        val legacyValues = serialized.split(',').filter(String::isNotBlank)
        return if (legacyValues.isEmpty()) {
            listOf(DEFAULT_WIFI)
        } else {
            normalizeWifiList(legacyValues)
        }
    }

    fun getAppearanceMode(): AppearanceMode {
        val storedValue = prefs.getString(KEY_APPEARANCE_MODE, null)
        return AppearanceMode.entries.firstOrNull { it.name == storedValue }
            ?: AppearanceMode.SYSTEM
    }

    override fun addTargetWifi(ssid: String) {
        val trimmed = ssid.trim()
        if (trimmed.isEmpty() || trimmed in _targetSsids.value) return
        persistTargetSsids(_targetSsids.value + trimmed)
        _targetWifiConfigChanges.tryEmit(
            TargetWifiConfigChange(TargetWifiConfigChangeType.ADDED, trimmed)
        )
    }

    /**
     * 将识别到的北工大宿舍 Wi-Fi 自动加入目标列表。调用方已在当前刷新任务内，
     * 因此不发送配置变更事件，以避免重复刷新。
     */
    override fun addAutoDetectedTargetWifi(ssid: String): Boolean {
        val trimmed = ssid.trim()
        if (!isBjutDormitoryWifi(trimmed) || trimmed in _targetSsids.value) return false
        persistTargetSsids(_targetSsids.value + trimmed)
        return true
    }

    override fun removeTargetWifi(ssid: String) {
        val updatedSsids = _targetSsids.value.filterNot { it == ssid }
        if (updatedSsids == _targetSsids.value) return
        persistTargetSsids(updatedSsids)
        prefs.edit().remove(KEY_WIFI_LOGIN_METHOD_PREFIX + ssid).apply()
        _targetWifiConfigChanges.tryEmit(
            TargetWifiConfigChange(TargetWifiConfigChangeType.REMOVED, ssid)
        )
    }

    private fun persistTargetSsids(list: List<String>) {
        val normalized = normalizeWifiList(list)
        prefs.edit().putString(KEY_TARGET_WIFIS, TargetWifiCodec.encode(normalized)).apply()
        _targetSsids.value = normalized
    }

    override fun getWifiLoginMethod(ssid: String): WifiLoginMethod? {
        if (ssid !in _targetSsids.value) return null
        val stored = prefs.getString(KEY_WIFI_LOGIN_METHOD_PREFIX + ssid, null)
        return WifiLoginMethod.entries.firstOrNull { it.storageValue == stored }
    }

    override fun saveWifiLoginMethod(ssid: String, method: WifiLoginMethod) {
        if (ssid !in _targetSsids.value || getWifiLoginMethod(ssid) == method) return
        prefs.edit().putString(KEY_WIFI_LOGIN_METHOD_PREFIX + ssid, method.storageValue).apply()
    }

    private fun normalizeWifiList(values: List<String>): List<String> = values
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    override suspend fun saveCredentials(username: String, password: String): CredentialSaveResult =
        withContext(Dispatchers.IO) {
            accountsMutex.withLock {
                initializeAccountsLocked()
                if (username.isBlank() || password.isBlank()) {
                    return@withLock CredentialSaveResult.INVALID_INPUT
                }
                persistAccounts(savedAccounts.save(username, password))
            }
        }

    override suspend fun selectAccount(username: String): CredentialSaveResult = withContext(Dispatchers.IO) {
        accountsMutex.withLock {
            initializeAccountsLocked()
            if (savedAccounts.accounts.none { it.username == username }) {
                return@withLock CredentialSaveResult.INVALID_INPUT
            }
            persistAccounts(savedAccounts.select(username))
        }
    }

    override suspend fun removeAccount(username: String): CredentialSaveResult = withContext(Dispatchers.IO) {
        accountsMutex.withLock {
            initializeAccountsLocked()
            persistAccounts(savedAccounts.remove(username))
        }
    }

    override suspend fun readActiveAccount(): SavedAccount? = withContext(Dispatchers.IO) {
        accountsMutex.withLock {
            initializeAccountsLocked()
            savedAccounts.active
        }
    }

    private fun persistAccounts(value: SavedAccounts): CredentialSaveResult {
        val encrypted = credentialCipher.encrypt(KEY_ACCOUNTS, SavedAccountsCodec.encode(value))
            ?: return CredentialSaveResult.ENCRYPTION_UNAVAILABLE
        val previous = securePrefs.getString(KEY_ACCOUNTS, null)
        val written = runCatching {
            securePrefs.edit().putString(KEY_ACCOUNTS, encrypted).commit()
        }.getOrDefault(false)
        if (!written) {
            // commit 失败也可能更新 SharedPreferences 内存，恢复旧值以免后续读取误认保存成功。
            runCatching {
                val editor = securePrefs.edit()
                if (previous == null) editor.remove(KEY_ACCOUNTS) else editor.putString(KEY_ACCOUNTS, previous)
                editor.commit()
            }
            return CredentialSaveResult.STORAGE_UNAVAILABLE
        }
        publishAccounts(value)
        // 清理失败时保留旧数据；新密文已经落盘且读取优先使用新格式，下次初始化重试清理。
        clearLegacyCredentials()
        return CredentialSaveResult.SAVED
    }

    private fun publishAccounts(value: SavedAccounts) {
        savedAccounts = value
        _accounts.value = value.accounts
        _selectedAccountUsername.value = value.active?.username.orEmpty()
        _password.value = value.active?.password.orEmpty()
    }

    private fun clearLegacyCredentials() {
        runCatching { securePrefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).commit() }
        runCatching { prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).commit() }
    }

    override fun saveAppearanceMode(mode: AppearanceMode) {
        prefs.edit().putString(KEY_APPEARANCE_MODE, mode.name).apply()
        _appearanceMode.value = mode
    }

    override fun setHttpLogEnabled(enabled: Boolean) {
        if (_httpLogEnabled.value == enabled) return
        prefs.edit().putBoolean(KEY_HTTP_LOG_ENABLED, enabled).apply()
        _httpLogEnabled.value = enabled
    }

    override fun setAutoLoginOnStartup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LOGIN_ON_STARTUP, enabled).apply()
        _autoLoginOnStartup.value = enabled
    }

    override fun getLastUpdateCheckAt(): Long =
        prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)

    override fun setLastUpdateCheckAt(timestampMillis: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, timestampMillis).apply()
    }

    override fun getUpdateCheckRetryAt(): Long = prefs.getLong(KEY_UPDATE_CHECK_RETRY_AT, 0L)

    override fun setUpdateCheckRetryAt(timestampMillis: Long) {
        prefs.edit().putLong(KEY_UPDATE_CHECK_RETRY_AT, timestampMillis).apply()
    }

    override fun acknowledgeCredentialReset() {
        _credentialResetPending.value = false
    }

    private fun readCredential(key: String): String {
        val stored = securePrefs.getString(key, null)
            ?: return prefs.getString(key, "") ?: ""
        return when (val outcome = credentialCipher.decrypt(key, stored)) {
            is CredentialDecryptOutcome.Success -> outcome.value
            CredentialDecryptOutcome.KeyInvalidated -> {
                handleCredentialKeyInvalidation()
                ""
            }

            CredentialDecryptOutcome.Corrupt,
            CredentialDecryptOutcome.NotEncrypted -> ""
        }
    }

    private fun readSavedAccounts(): SavedAccounts {
        if (securePrefs.contains(KEY_ACCOUNTS)) {
            val decoded = SavedAccountsCodec.decode(readCredential(KEY_ACCOUNTS))
            if (decoded == null) _credentialResetPending.value = true
            if (decoded != null) clearLegacyCredentials()
            return decoded ?: SavedAccounts()
        }
        val username = readCredential(KEY_USERNAME)
        val password = readCredential(KEY_PASSWORD)
        if (_credentialResetPending.value || username.isBlank()) return SavedAccounts()
        return SavedAccounts(listOf(SavedAccount(username, password)), username)
    }

    private fun handleCredentialKeyInvalidation() {
        // 密钥已经没了，密文无法再解开：连同密钥集、旧密文和可能的明文残留一起清除，
        // 下次保存时生成全新密钥。界面收到事件后提示用户重新填写。
        credentialCipher.resetAfterInvalidation()
        securePrefs.edit().clear().apply()
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        _credentialResetPending.value = true
    }

    companion object {
        private const val KEY_TARGET_WIFIS = "target_wifis"
        private const val KEY_WIFI_LOGIN_METHOD_PREFIX = "wifi_login_method_v1:"
        private const val KEY_ACCOUNTS = "saved_accounts_v1"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
        private const val KEY_AUTO_LOGIN_ON_STARTUP = "auto_login_on_startup"
        private const val KEY_HTTP_LOG_ENABLED = "http_log_enabled"
        private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"
        private const val KEY_UPDATE_CHECK_RETRY_AT = "update_check_retry_at"
        private const val DEFAULT_WIFI = "bjut_wifi"
    }
}
