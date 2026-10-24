package top.steins.autologin.domain.model

data class SavedAccount(val username: String, val password: String) {
    override fun toString(): String = "SavedAccount(username=$username, password=***)"
}
