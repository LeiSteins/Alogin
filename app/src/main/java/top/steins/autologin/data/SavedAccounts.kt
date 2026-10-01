package top.steins.autologin.data

import org.json.JSONArray
import org.json.JSONObject

data class SavedAccount(val username: String, val password: String) {
    override fun toString(): String = "SavedAccount(username=$username, password=***)"
}

internal data class SavedAccounts(
    val accounts: List<SavedAccount> = emptyList(),
    val activeUsername: String = ""
) {
    val active: SavedAccount? get() = accounts.find { it.username == activeUsername }

    fun save(username: String, password: String): SavedAccounts {
        val account = SavedAccount(username.trim(), password)
        require(account.username.isNotBlank() && password.isNotBlank())
        val updated = if (accounts.any { it.username == account.username }) {
            accounts.map { if (it.username == account.username) account else it }
        } else {
            accounts + account
        }
        return SavedAccounts(updated, account.username)
    }

    fun select(username: String): SavedAccounts {
        require(accounts.any { it.username == username })
        return copy(activeUsername = username)
    }

    fun remove(username: String): SavedAccounts {
        val remaining = accounts.filterNot { it.username == username }
        return SavedAccounts(
            remaining,
            if (activeUsername == username) remaining.firstOrNull()?.username.orEmpty()
            else activeUsername
        )
    }
}

/** 显式 JSON 字段映射，不依赖反射；完整内容交由 CredentialCipher 加密后落盘。 */
internal object SavedAccountsCodec {
    fun encode(value: SavedAccounts): String = JSONObject()
        .put("version", 1)
        .put("active", value.activeUsername)
        .put("accounts", JSONArray().apply {
            value.accounts.forEach {
                put(JSONObject().put("username", it.username).put("password", it.password))
            }
        }).toString()

    fun decode(value: String): SavedAccounts? = runCatching {
        val root = JSONObject(value)
        require(root.getInt("version") == 1)
        val array = root.getJSONArray("accounts")
        val accounts = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            SavedAccount(item.getString("username"), item.getString("password")).also {
                require(it.username.isNotBlank())
            }
        }
        require(accounts.distinctBy { it.username }.size == accounts.size)
        val active = root.getString("active")
        require(if (accounts.isEmpty()) active.isEmpty() else accounts.any { it.username == active })
        SavedAccounts(accounts, active)
    }.getOrNull()
}
