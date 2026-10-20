package top.steins.autologin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.steins.autologin.data.SettingsGateway
import top.steins.autologin.network.NetworkEnvironment
import top.steins.autologin.network.SelfServiceGateway
import top.steins.autologin.network.WifiScanOutcome
import top.steins.autologin.network.update.UpdateGateway
import top.steins.autologin.state.AccountStateHolder
import top.steins.autologin.state.CampusSessionCoordinator
import top.steins.autologin.state.HttpLogStateHolder
import top.steins.autologin.state.SettingsStateHolder
import top.steins.autologin.state.UpdateStateHolder
import top.steins.autologin.state.WifiScanStateHolder

/** 应用生命周期容器：组装各功能状态并连接跨功能事件，不承担页面业务。 */
class AppViewModel(
    strings: AppStrings,
    settings: SettingsGateway,
    selfService: SelfServiceGateway,
    updates: UpdateGateway,
    network: NetworkEnvironment,
    hasLocationPermission: () -> Boolean,
    currentTimeMillis: () -> Long = System::currentTimeMillis,
    scanWifi: suspend () -> WifiScanOutcome
) : ViewModel() {
    val accounts = AccountStateHolder(settings)
    val logs = HttpLogStateHolder(settings)
    val update = UpdateStateHolder(
        scope = viewModelScope,
        strings = strings,
        settings = settings,
        updates = updates,
        hasValidatedInternet = network::hasValidatedInternet,
        currentVersion = BuildConfig.VERSION_NAME,
        currentVersionCode = BuildConfig.VERSION_CODE,
        currentTimeMillis = currentTimeMillis,
        automaticCheckDelayMs = UpdateStateHolder.AUTOMATIC_UPDATE_CHECK_DELAY_MS,
        automaticCheckIntervalMs = UpdateStateHolder.AUTOMATIC_UPDATE_CHECK_INTERVAL_MS
    )
    val session = CampusSessionCoordinator(
        scope = viewModelScope,
        strings = strings,
        settings = settings,
        selfService = selfService,
        network = network,
        hasLocationPermission = hasLocationPermission,
        onLoginStarted = update::cancelForLogin,
        onLoginSucceeded = update::scheduleAutomaticCheck
    )
    val preferences = SettingsStateHolder(settings, session::cancelStartupAutoLogin)
    val wifiScan = WifiScanStateHolder(viewModelScope, scanWifi, strings)

    override fun onCleared() {
        session.close()
    }
}
