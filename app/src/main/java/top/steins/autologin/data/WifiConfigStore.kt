package top.steins.autologin.data

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TargetWifiConfigChangeType {
    ADDED,
    REMOVED
}

data class TargetWifiConfigChange(
    val type: TargetWifiConfigChangeType,
    val ssid: String
)

interface WifiConfigStore {
    val targetSsids: StateFlow<List<String>>
    val targetWifiConfigChanges: SharedFlow<TargetWifiConfigChange>

    fun addAutoDetectedTargetWifi(ssid: String): Boolean
    fun addTargetWifi(ssid: String)
    fun removeTargetWifi(ssid: String)
    fun getWifiLoginMethod(ssid: String): WifiLoginMethod?
    fun saveWifiLoginMethod(ssid: String, method: WifiLoginMethod)
}
