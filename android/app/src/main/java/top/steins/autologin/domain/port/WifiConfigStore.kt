package top.steins.autologin.domain.port

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import top.steins.autologin.domain.model.TargetWifiConfigChange
import top.steins.autologin.domain.model.WifiLoginMethod

interface WifiConfigStore {
    val targetSsids: StateFlow<List<String>>
    val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange>

    fun addAutoDetectedTargetWifi(ssid: String): Boolean
    fun addTargetWifi(ssid: String)
    fun removeTargetWifi(ssid: String)
    fun getWifiLoginMethod(ssid: String): WifiLoginMethod?
    fun saveWifiLoginMethod(ssid: String, method: WifiLoginMethod)
}
