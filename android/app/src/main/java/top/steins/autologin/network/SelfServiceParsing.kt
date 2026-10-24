package top.steins.autologin.network

import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import top.steins.autologin.domain.model.AccountDevice

internal sealed interface JsonObjectExtraction {
    data class Found(val text: String) : JsonObjectExtraction

    /** 页面中没有找到 JSON 对象。 */
    data object Missing : JsonObjectExtraction

    /** 找到了 `{` 但没有匹配的 `}`。 */
    data object Incomplete : JsonObjectExtraction
}

/**
 * 自助服务页面解析的纯函数集合，不依赖 Android 上下文，便于单元测试。
 * 异常消息的本地化与提示由调用方负责。
 */
internal object SelfServiceParsing {
    /**
     * 从 JSONP / 内嵌脚本混合文本中定位第一个平衡的 JSON 对象。
     * [marker] 非空时从该标记最后一次出现的位置开始搜索。
     */
    fun findJsonObject(text: String, marker: String? = null): JsonObjectExtraction {
        val searchStart = marker?.let { text.indexOf(it).takeIf { index -> index >= 0 } } ?: 0
        val objectStart = text.indexOf('{', searchStart)
        if (objectStart < 0) return JsonObjectExtraction.Missing

        var depth = 0
        var inString = false
        var isEscaped = false
        for (index in objectStart until text.length) {
            val char = text[index]
            when (char) {
                '\\' -> if (inString) isEscaped = !isEscaped
                '"' -> if (!isEscaped) inString = !inString
                '{' -> if (!inString) depth += 1
                '}' -> if (!inString) {
                    depth -= 1
                    if (depth == 0) {
                        return JsonObjectExtraction.Found(text.substring(objectStart, index + 1))
                    }
                }
            }
            if (char != '\\') isEscaped = false
        }
        return JsonObjectExtraction.Incomplete
    }

    /**
     * 解析 dashboard `getOnlineList` 返回的在线会话数组。
     * 空数组表示当前没有在线设备；格式非法或缺少下线操作所需字段时返回 null。
     */
    fun parseOnlineDevices(response: String): List<AccountDevice>? {
        val rows = try {
            JSONArray(response)
        } catch (_: JSONException) {
            return null
        }
        val devices = mutableListOf<AccountDevice>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: return null
            val sessionId = row.optString("sessionId").trim()
            val ipAddress = row.optString("ip").trim()
            val mac = canonicalMac(row.optString("mac")) ?: return null
            if (sessionId.isBlank() || ipAddress.isBlank()) return null

            devices += AccountDevice(
                sessionId = sessionId,
                macAddress = formatMac(mac),
                ipAddress = ipAddress,
                ipv6Address = row.optString("ipv6").trim(),
                loginTime = row.optString("loginTime").trim(),
                useTimeSeconds = row.optString("useTime").trim(),
                downFlow = row.optString("downFlow").trim(),
                upFlow = row.optString("upFlow").trim(),
                hostName = row.optString("hostName").trim(),
                terminalType = row.optString("terminalType").trim()
            )
        }
        return devices
    }

    fun canonicalMac(value: String): String? {
        val mac = value.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        return mac.takeIf {
            it.length == 12 && it.all { char -> char in '0'..'9' || char in 'A'..'F' }
        }
    }

    fun formatMac(mac: String): String = mac.chunked(2).joinToString(":")

    fun xorEncode(value: String): String = buildString(value.length * 2) {
        value.forEach { char ->
            append((char.code xor 0x16).toString(16).padStart(2, '0'))
        }
    }
}
