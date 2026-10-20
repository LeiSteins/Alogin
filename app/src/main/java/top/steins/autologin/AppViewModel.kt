package top.steins.autologin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.steins.autologin.data.AccountStore
import top.steins.autologin.data.AppPreferences
import top.steins.autologin.data.HttpLogPreferences
import top.steins.autologin.data.UpdateCheckStore
import top.steins.autologin.data.WifiConfigStore
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
    accountStore: AccountStore,
    wifiConfig: WifiConfigStore,
    appPreferences: AppPreferences,
    logPreferences: HttpLogPreferences,
    updateCheckStore: UpdateCheckStore,
    selfService: SelfServiceGateway,
    updates: UpdateGateway,
    network: NetworkEnvironment,
    hasLocationPermission: () -> Boolean,
    currentTimeMillis: () -> Long = System::currentTimeMillis,
    scanWifi: suspend () -> WifiScanOutcome
) : ViewModel() {
    val accounts = AccountStateHolder(accountStore)
    val logs = HttpLogStateHolder(logPreferences)
    val update = UpdateStateHolder(
        scope = viewModelScope,
        strings = strings,
        checkStore = updateCheckStore,
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
        accounts = accountStore,
        wifiConfig = wifiConfig,
        preferences = appPreferences,
        selfService = selfService,
        network = network,
        hasLocationPermission = hasLocationPermission,
        onLoginStarted = update::cancelForLogin,
        onLoginSucceeded = update::scheduleAutomaticCheck
    )
    val preferences = SettingsStateHolder(wifiConfig, appPreferences, session::cancelStartupAutoLogin)
    val wifiScan = WifiScanStateHolder(viewModelScope, scanWifi, strings)

    override fun onCleared() {
        session.close()
    }
}
