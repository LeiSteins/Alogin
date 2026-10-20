package top.steins.autologin.ui.format

import java.util.Locale

fun formatTrafficMegabytes(trafficMegabytes: String): String {
    val value = trafficMegabytes.trim()
    val mb = value.toDoubleOrNull()
        ?: return if (value.contains("MB", ignoreCase = true)) value else "$value MB"
    return when {
        mb >= 1024 -> String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1f MB", mb)
    }
}
