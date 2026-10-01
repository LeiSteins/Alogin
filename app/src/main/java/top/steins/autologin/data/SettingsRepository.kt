package top.steins.autologin.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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
    INVALID_INPUT
}

/**
 * 设置数据的读取与写入入口，屏蔽 SharedPreferences / Keystore 细节，
 * 使 ViewModel 与界面层不直接依赖存储实现，也便于单元测试注入替身。
 */
interface SettingsGateway {
    val targetWifis: StateFlow<List<String>>
    val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange>
    val accounts: StateFlow<List<SavedAccount>>
    val username: StateFlow<String>
    val password: StateFlow<String>
    val appearanceMode: StateFlow<AppearanceMode>
    val httpLogEnabled: StateFlow<Boolean>
    val credentialResetPending: StateFlow<Boolean>

    fun addAutoDetectedTargetWifi(ssid: String): Boolean

    fun addTargetWifi(ssid: String)

    fun removeTargetWifi(ssid: String)

    fun saveCredentials(username: String, password: String): CredentialSaveResult

    fun selectAccount(username: String): CredentialSaveResult

    fun removeAccount(username: String): CredentialSaveResult

    fun saveAppearanceMode(mode: AppearanceMode)

    fun setHttpLogEnabled(enabled: Boolean)

    fun getLastUpdateCheckAt(): Long

    fun setLastUpdateCheckAt(timestampMillis: Long)

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

    private val _targetWifis = MutableStateFlow(getTargetWifis())
    override val targetWifis: StateFlow<List<String>> = _targetWifis.asStateFlow()

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

    private var savedAccounts = readSavedAccounts()
    private val _accounts = MutableStateFlow(savedAccounts.accounts)
    override val accounts: StateFlow<List<SavedAccount>> = _accounts.asStateFlow()
    private val _username = MutableStateFlow(savedAccounts.active?.username.orEmpty())
    override val username: StateFlow<String> = _username.asStateFlow()
    private val _password = MutableStateFlow(savedAccounts.active?.password.orEmpty())
    override val password: StateFlow<String> = _password.asStateFlow()

    init {
        // 新格式加密成功后才删除旧凭据，迁移失败仍保留旧账号。
        if (!securePrefs.contains(KEY_ACCOUNTS) && savedAccounts.accounts.isNotEmpty()) {
            persistAccounts(savedAccounts)
        }
    }

    private val _appearanceMode = MutableStateFlow(getAppearanceMode())
    override val appearanceMode: StateFlow<AppearanceMode> = _appearanceMode.asStateFlow()

    private val _httpLogEnabled = MutableStateFlow(prefs.getBoolean(KEY_HTTP_LOG_ENABLED, false))
    override val httpLogEnabled: StateFlow<Boolean> = _httpLogEnabled.asStateFlow()

    private fun getTargetWifis(): List<String> {
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
        if (trimmed.isEmpty() || trimmed in _targetWifis.value) return
        persistWifis(_targetWifis.value + trimmed)
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
        if (!isBjutDormitoryWifi(trimmed) || trimmed in _targetWifis.value) return false
        persistWifis(_targetWifis.value + trimmed)
        return true
    }

    override fun removeTargetWifi(ssid: String) {
        val updatedWifis = _targetWifis.value.filterNot { it == ssid }
        if (updatedWifis == _targetWifis.value) return
        persistWifis(updatedWifis)
        _targetWifiConfigChanges.tryEmit(
            TargetWifiConfigChange(TargetWifiConfigChangeType.REMOVED, ssid)
        )
    }

    private fun persistWifis(list: List<String>) {
        val normalized = normalizeWifiList(list)
        prefs.edit().putString(KEY_TARGET_WIFIS, TargetWifiCodec.encode(normalized)).apply()
        _targetWifis.value = normalized
    }

    private fun normalizeWifiList(values: List<String>): List<String> = values
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    override fun saveCredentials(username: String, password: String): CredentialSaveResult {
        if (username.isBlank() || password.isBlank()) return CredentialSaveResult.INVALID_INPUT
        return persistAccounts(savedAccounts.save(username, password))
    }

    override fun selectAccount(username: String): CredentialSaveResult {
        if (savedAccounts.accounts.none { it.username == username }) {
            return CredentialSaveResult.INVALID_INPUT
        }
        return persistAccounts(savedAccounts.select(username))
    }

    override fun removeAccount(username: String): CredentialSaveResult =
        persistAccounts(savedAccounts.remove(username))

    private fun persistAccounts(value: SavedAccounts): CredentialSaveResult {
        val encrypted = credentialCipher.encrypt(KEY_ACCOUNTS, SavedAccountsCodec.encode(value))
            ?: return CredentialSaveResult.ENCRYPTION_UNAVAILABLE
        securePrefs.edit()
            .putString(KEY_ACCOUNTS, encrypted)
            .remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
        savedAccounts = value
        _accounts.value = value.accounts
        _username.value = value.active?.username.orEmpty()
        _password.value = value.active?.password.orEmpty()
        return CredentialSaveResult.SAVED
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

    override fun getLastUpdateCheckAt(): Long =
        prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)

    override fun setLastUpdateCheckAt(timestampMillis: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, timestampMillis).apply()
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
        private const val KEY_ACCOUNTS = "saved_accounts_v1"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_APPEARANCE_MODE = "appearance_mode"
        private const val KEY_HTTP_LOG_ENABLED = "http_log_enabled"
        private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"
        private const val DEFAULT_WIFI = "bjut_wifi"
    }
}
