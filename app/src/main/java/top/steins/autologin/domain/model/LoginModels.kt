package top.steins.autologin.domain.model

data class LoginStatus(
    val isLoggedIn: Boolean,
    val uid: String = "",
    val flow: String = "",
    val time: String = "",
    val v4ip: String = "",
    val error: String = "",
    val verifiedMethod: WifiLoginMethod? = null
)

sealed class LoginResult {
    data object Success : LoginResult()
    data object AlreadyOnline : LoginResult()
    data class Failure(val message: String) : LoginResult()
    data class NetworkError(val message: String) : LoginResult()
}
