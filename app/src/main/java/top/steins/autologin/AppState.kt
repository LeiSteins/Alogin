package top.steins.autologin

import androidx.annotation.StringRes
import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.SeamlessStatus

/** 字符串解析入口，让 ViewModel 不依赖 Android Context。 */
fun interface AppStrings {
    fun get(@StringRes resId: Int, vararg formatArgs: Any): String
}

data class AppUiState(
    val wifiName: String = "",
    val ipAddress: String = "",
    val isOnline: Boolean = false,
    val isLoggingIn: Boolean = false,
    val loginMessage: String? = null,
    val accountOverview: AccountOverview? = null,
    val seamlessStatus: SeamlessStatus = SeamlessStatus.Unknown,
    val isAccountInfoLoading: Boolean = false,
    val accountInfoError: String = "",
    val isDeviceListAvailable: Boolean = false,
    val canLogoutDevices: Boolean = false,
    val networkStatusError: String = "",
    val hasLocationPermission: Boolean = false
)
