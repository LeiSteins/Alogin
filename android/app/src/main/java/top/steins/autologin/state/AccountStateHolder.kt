package top.steins.autologin.state

import top.steins.autologin.domain.port.AccountStore

/** 账号页面及凭据恢复提示所需的状态与操作。 */
class AccountStateHolder(private val accountStore: AccountStore) {
    val accounts = accountStore.accounts
    val selectedAccountUsername = accountStore.selectedAccountUsername
    val credentialResetPending = accountStore.credentialResetPending

    suspend fun saveCredentials(username: String, password: String) =
        accountStore.saveCredentials(username, password)

    suspend fun selectAccount(username: String) = accountStore.selectAccount(username)

    suspend fun removeAccount(username: String) = accountStore.removeAccount(username)

    fun acknowledgeCredentialReset() = accountStore.acknowledgeCredentialReset()
}
