package top.steins.autologin.data

/**
 * 使用长度前缀保存 SSID 列表，既能保留添加顺序，也不会被 SSID 中的逗号拆坏。
 */
internal object TargetWifiCodec {
    private const val PREFIX = "v1|"

    fun encode(ssids: List<String>): String = buildString {
        append(PREFIX)
        ssids.forEach { ssid ->
            append(ssid.length)
            append(':')
            append(ssid)
        }
    }

    /**
     * 返回 null 代表旧版逗号分隔格式或损坏数据，需要调用方按旧格式迁移。
     */
    fun decode(serialized: String): List<String>? {
        if (!serialized.startsWith(PREFIX)) return null

        val ssids = mutableListOf<String>()
        var cursor = PREFIX.length
        while (cursor < serialized.length) {
            val separator = serialized.indexOf(':', cursor)
            if (separator < 0) return null
            val length = serialized.substring(cursor, separator).toIntOrNull() ?: return null
            if (length < 0) return null

            val valueStart = separator + 1
            val valueEnd = valueStart + length
            if (valueEnd > serialized.length) return null
            ssids += serialized.substring(valueStart, valueEnd)
            cursor = valueEnd
        }
        return ssids
    }
}
