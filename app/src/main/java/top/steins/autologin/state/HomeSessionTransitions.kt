package top.steins.autologin.state

import top.steins.autologin.HomeUiState
import top.steins.autologin.domain.model.AccountOverviewResult
import top.steins.autologin.domain.model.CurrentNetworkInfo
import top.steins.autologin.domain.model.SeamlessStatus

/** 只计算展示状态；写入和刷新代次检查由 CampusSessionCoordinator 独占。 */
internal fun HomeUiState.withoutAccount(
    info: CurrentNetworkInfo,
    online: Boolean = false,
    error: String = ""
): HomeUiState = copy(
    wifiName = info.wifiName,
    ipAddress = info.ipAddress,
    isOnline = online,
    accountOverview = null,
    seamlessStatus = SeamlessStatus.Unknown,
    isAccountInfoLoading = false,
    accountInfoError = error,
    isDeviceListAvailable = false,
    canLogoutDevices = false,
    networkStatusError = ""
)

internal fun HomeUiState.authenticationUnavailable(
    info: CurrentNetworkInfo,
    error: String,
    showFinalError: Boolean
): HomeUiState = copy(
    wifiName = if (isOnline) wifiName else info.wifiName,
    ipAddress = if (isOnline) ipAddress else info.ipAddress,
    isAccountInfoLoading = !showFinalError,
    accountInfoError = if (isOnline && showFinalError) error else accountInfoError,
    networkStatusError = if (!isOnline && showFinalError) error else networkStatusError,
    canLogoutDevices = false
)

internal fun HomeUiState.authenticated(info: CurrentNetworkInfo, username: String): HomeUiState {
    // 同一认证账号刷新时保留旧数据；切换账号后不得沿用上一账号的内容。
    val keepOverview = isOnline && accountOverview?.username == username
    return copy(
        wifiName = info.wifiName,
        ipAddress = info.ipAddress,
        isOnline = true,
        accountOverview = if (keepOverview) accountOverview else null,
        seamlessStatus = if (keepOverview) seamlessStatus else SeamlessStatus.Unknown,
        isAccountInfoLoading = true,
        accountInfoError = if (keepOverview) accountInfoError else "",
        isDeviceListAvailable = keepOverview && isDeviceListAvailable,
        canLogoutDevices = false,
        networkStatusError = ""
    )
}

internal fun HomeUiState.accountOverviewLoaded(
    result: AccountOverviewResult,
    showFinalError: Boolean
): HomeUiState = when (result) {
    is AccountOverviewResult.Success -> copy(
        isOnline = true,
        accountOverview = result.overview,
        seamlessStatus = if (accountOverview?.username == result.overview.username) seamlessStatus else SeamlessStatus.Unknown,
        isAccountInfoLoading = false,
        accountInfoError = result.warningMessage,
        isDeviceListAvailable = result.isDeviceListAvailable,
        canLogoutDevices = result.canLogoutDevices
    )
    is AccountOverviewResult.Failure -> copy(
        isOnline = true,
        isAccountInfoLoading = result.isRetryable && !showFinalError,
        accountInfoError = if (!result.isRetryable || showFinalError) result.message else accountInfoError,
        canLogoutDevices = false
    )
}
