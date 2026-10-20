package top.steins.autologin.data

/** 固定存储值，避免枚举名称调整影响已记录的 Wi-Fi 验证方式。 */
enum class WifiLoginMethod(val storageValue: String) {
    WLGN("wlgn"),
    EPORTAL("eportal")
}
