package top.steins.autologin.network

import java.util.Base64
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/** 门户先返回登录页模板，再通过脚本查询在线状态，模板标题不能作为离线依据。 */
internal fun ByteArray.isDynamicCampusPortal(): Boolean =
    DYNAMIC_PORTAL_PATTERN.containsMatchIn(toString(Charsets.ISO_8859_1))

internal suspend fun fetchDynamicPortalStatus(
    client: OkHttpClient,
    pageRequest: Request,
    pageBody: ByteArray,
    wlanUserIp: String
): LoginRecheckResult {
    val page = pageBody.toString(Charsets.ISO_8859_1)
    val ipAddress = wlanUserIp.ifBlank { page.pageVariable("v46ip").ifBlank { page.pageVariable("ss5") } }
    if (!ipAddress.isUsableIpv4()) return LoginRecheckResult.Unknown

    // 门户脚本的 API 端口为 HTTP 801、HTTPS 802，配置与页面使用不同端口。
    // 只使用原门户主机上的固定接口，不执行页面脚本或采用页面提供的任意 URL。
    val apiBase = pageRequest.url.newBuilder()
        .port(if (pageRequest.url.isHttps) 802 else 801)
        .encodedPath("/eportal/portal/")
        .query(null)
        .fragment(null)
        .build()
    val configUrl = apiBase.newBuilder()
        .addPathSegments("page/loadConfig")
        .addQueryParameter("callback", "dr1003")
        .addQueryParameter("program_index", "")
        .addQueryParameter("wlan_vlan_id", page.pageVariable("vlanid").ifBlank { "0" })
        .addQueryParameter("wlan_user_ip", Base64.getEncoder().encodeToString(ipAddress.toByteArray()))
        .addQueryParameter("wlan_user_ipv6", "")
        .addQueryParameter("wlan_user_ssid", "")
        .addQueryParameter("wlan_user_areaid", "")
        .addQueryParameter("wlan_ac_ip", "")
        .addQueryParameter("wlan_ap_mac", "")
        .addQueryParameter("gw_id", "")
        .addQueryParameter("jsVersion", "4.X")
        .build()
    val config = fetchPortalJson(client, pageRequest, configUrl)?.optJSONObject("data")
        ?: return LoginRecheckResult.Unknown
    val statusUrl = when (config.opt("check_online_method")?.toString()) {
        "0" -> pageRequest.url.newBuilder()
            .encodedPath("/drcom/chkstatus")
            .query(null)
            .fragment(null)
        "1" -> {
            val decimalIp = ipAddress.split('.').fold(0L) { value, part ->
                (value shl 8) or part.toLong()
            }.toString()
            apiBase.newBuilder()
                .addPathSegment("online_list")
                .addQueryParameter("user_account", "")
                .addQueryParameter("user_password", "")
                .addQueryParameter("wlan_user_mac", page.pageVariable("ss4").uppercase())
                .addQueryParameter("wlan_user_ip", decimalIp)
                .addQueryParameter("curr_user_ip", decimalIp)
        }
        else -> return LoginRecheckResult.Unknown
    }.addQueryParameter("callback", "dr1003")
        .addQueryParameter("jsVersion", "4.X")
        .build()
    val status = fetchPortalJson(client, pageRequest, statusUrl) ?: return LoginRecheckResult.Unknown
    return when (status.opt("result")?.toString()) {
        "1" -> LoginRecheckResult.LoggedIn
        "0" -> LoginRecheckResult.LoggedOut
        else -> LoginRecheckResult.Unknown
    }
}

private suspend fun fetchPortalJson(
    client: OkHttpClient,
    pageRequest: Request,
    url: HttpUrl
): JSONObject? {
    val request = pageRequest.newBuilder()
        .url(url)
        .header("Cache-Control", "no-cache")
        .get()
        .build()
    client.executeCancellable(request).use { response ->
        if (!response.isSuccessful) return null
        val extraction = SelfServiceParsing.findJsonObject(response.body?.string().orEmpty())
        if (extraction !is JsonObjectExtraction.Found) return null
        return try {
            JSONObject(extraction.text)
        } catch (_: JSONException) {
            null
        }
    }
}

private fun String.pageVariable(name: String): String =
    Regex("""\b$name\s*=\s*["']([^"']*)["']""")
        .find(this)?.groupValues?.get(1)?.trim().orEmpty()

private val DYNAMIC_PORTAL_PATTERN = Regex("""\bpage\s*\.\s*run\s*\(|\ba41\.js\b""")
