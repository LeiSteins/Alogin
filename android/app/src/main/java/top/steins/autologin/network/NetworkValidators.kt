package top.steins.autologin.network

internal fun String.isUsableIpv4(): Boolean {
    val parts = trim().split('.')
    return parts.size == 4 && parts.all { part ->
        val value = part.toIntOrNull()
        part.isNotEmpty() && value != null && value in 0..255
    }
}
