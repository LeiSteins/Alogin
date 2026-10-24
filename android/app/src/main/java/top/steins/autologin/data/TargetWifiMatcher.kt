package top.steins.autologin.data

private const val BJUT_DORMITORY_WIFI_PREFIX = "bjut-sushe-"

/** Returns whether an SSID belongs to the BJUT dormitory Wi-Fi family. */
internal fun isBjutDormitoryWifi(ssid: String): Boolean =
    ssid.startsWith(BJUT_DORMITORY_WIFI_PREFIX)
