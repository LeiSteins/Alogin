package top.steins.autologin.domain.rule

/** 只接受完整的十六进制 MAC 地址和统一分隔符，不丢弃非法字符。 */
fun canonicalMac(value: String): String? {
    val trimmed = value.trim()
    if (!trimmed.matches(Regex("(?:[a-fA-F0-9]{12}|(?:[a-fA-F0-9]{2}:){5}[a-fA-F0-9]{2}|(?:[a-fA-F0-9]{2}-){5}[a-fA-F0-9]{2})"))) return null
    return trimmed.replace(":", "").replace("-", "").uppercase(java.util.Locale.ROOT)
}
