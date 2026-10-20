package top.steins.autologin.domain.model

enum class TargetWifiConfigChangeType {
    ADDED,
    REMOVED
}

data class TargetWifiConfigChange(
    val type: TargetWifiConfigChangeType,
    val ssid: String
)
