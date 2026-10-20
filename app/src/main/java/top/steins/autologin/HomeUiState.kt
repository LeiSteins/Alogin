package top.steins.autologin

import top.steins.autologin.network.AccountOverview
import top.steins.autologin.network.SeamlessStatus

data class HomeUiState(
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
