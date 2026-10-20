package top.steins.autologin.state

import top.steins.autologin.data.SettingsGateway

/** 账号页面及凭据恢复提示所需的状态与操作。 */
class AccountStateHolder(private val settings: SettingsGateway) {
    val accounts = settings.accounts
    val selectedAccountUsername = settings.selectedAccountUsername
    val credentialResetPending = settings.credentialResetPending

    suspend fun saveCredentials(username: String, password: String) =
        settings.saveCredentials(username, password)

    suspend fun selectAccount(username: String) = settings.selectAccount(username)

    suspend fun removeAccount(username: String) = settings.removeAccount(username)

    fun acknowledgeCredentialReset() = settings.acknowledgeCredentialReset()
}
