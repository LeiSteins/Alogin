package top.steins.autologin.data

import kotlinx.coroutines.flow.StateFlow

/** 凭据保存结果；加密不可用时不落盘。 */
enum class CredentialSaveResult {
    SAVED,
    ENCRYPTION_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    INVALID_INPUT
}

/** 账号操作共用同一初始化、加密与持久化事务边界。 */
interface AccountStore {
    /** 在后台完成凭据读取与迁移；认证前必须等待初始化。 */
    suspend fun initialize()

    val accounts: StateFlow<List<SavedAccount>>
    val selectedAccountUsername: StateFlow<String>
    val password: StateFlow<String>
    val credentialResetPending: StateFlow<Boolean>

    suspend fun saveCredentials(username: String, password: String): CredentialSaveResult
    suspend fun selectAccount(username: String): CredentialSaveResult
    suspend fun removeAccount(username: String): CredentialSaveResult
    suspend fun readActiveAccount(): SavedAccount?
    fun acknowledgeCredentialReset()
}
