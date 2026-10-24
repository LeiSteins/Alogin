package top.steins.autologin.network

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/** 仅从固定门户读取本次登录 IP，不使用本机网卡地址，也不把 chkstatus 的 result 当作在线状态。 */
internal suspend fun fetchEportalIpv4(client: OkHttpClient, isCurrent: () -> Boolean): String? {
    for (path in listOf("/drcom/chkstatus?callback=dr1003", "/")) {
        requireCurrentLoginNetwork(isCurrent)
        val address = try {
            val request = Request.Builder()
                .url("http://10.21.221.98$path")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            client.executeCancellable(request).use { response ->
                if (!response.isSuccessful) null
                else {
                    val body = response.body?.string().orEmpty()
                    if (path == "/") portalPageIpv4(body) else portalStatusIpv4(body)
                }
            }
        } catch (_: IOException) {
            null
        }
        requireCurrentLoginNetwork(isCurrent)
        if (address != null) return address
    }
    return null
}

internal fun portalPageIpv4(page: String): String? = PORTAL_IP_FIELDS.firstNotNullOfOrNull { field ->
    val value = Regex("""\b$field\s*=\s*["']([^"']*)["']""")
        .find(page)?.groupValues?.get(1)
    validPortalIpv4(value)
}

internal fun portalStatusIpv4(body: String): String? {
    val extraction = SelfServiceParsing.findJsonObject(body)
    if (extraction !is JsonObjectExtraction.Found) return null
    return try {
        val json = JSONObject(extraction.text)
        PORTAL_IP_FIELDS.firstNotNullOfOrNull { field -> validPortalIpv4(json.opt(field) as? String) }
    } catch (_: JSONException) {
        null
    }
}

private fun validPortalIpv4(value: String?): String? {
    val ip = value?.trim() ?: return null
    val parts = ip.split('.')
    if (parts.size != 4 || parts.any { part ->
            part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                (part.length > 1 && part.startsWith('0')) || (part.toIntOrNull() ?: 256) > 255
        }) return null
    val octets = parts.map(String::toInt)
    if (octets.all { it == 0 } || octets[0] == 127 || octets[0] >= 224 ||
        (octets[0] == 169 && octets[1] == 254)) return null
    return ip
}

internal fun requireCurrentLoginNetwork(isCurrent: () -> Boolean) {
    if (!isCurrent()) throw LoginNetworkChangedException()
}

internal class LoginNetworkChangedException : IOException()

private val PORTAL_IP_FIELDS = listOf("v46ip", "ss5", "v4ip")
