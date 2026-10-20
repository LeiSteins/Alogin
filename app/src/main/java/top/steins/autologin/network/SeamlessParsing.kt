package top.steins.autologin.network

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** 只解析页面数据，不执行服务器返回的 HTML 或 JavaScript。 */
internal object SeamlessParsing {
    fun parseStatus(response: String): SeamlessStatus {
        val html = try {
            JSONTokener(response).nextValue() as? String ?: return SeamlessStatus.Unknown
        } catch (_: JSONException) {
            return SeamlessStatus.Unknown
        }
        val text = html.replace(Regex("<[^>]*>"), "").trim()
        return when (text) {
            "开启", "已开启" -> SeamlessStatus.Enabled
            "关闭", "已关闭", "未开启" -> SeamlessStatus.Disabled
            else -> SeamlessStatus.Unknown
        }
    }

    fun parseDevices(response: String): BoundDeviceList? {
        return try {
            val data = JSONObject(response)
            val total = (data.opt("total") as? Number)?.let {
                it.toInt().takeIf { value -> value >= 0 && it.toDouble() == value.toDouble() }
            } ?: return null
            val rows = data.optJSONArray("rows") ?: return null
            val devices = buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONArray(index) ?: return null
                    if (row.length() < 5 || (0..4).any { row.opt(it) !is String }) return null
                    val mac = canonicalMac(row.getString(1)) ?: return null
                    add(BoundDevice(
                        macAddress = SelfServiceParsing.formatMac(mac),
                        isOnline = when (row.getString(0)) { "0" -> false; "1" -> true; else -> null },
                        terminalInfo = row.getString(2).removePrefix("#").trim(),
                        lastLoginTime = row.getString(3).trim(),
                        lastLoginIp = row.getString(4).trim()
                    ))
                }
            }
            if (total < devices.size || devices.map { it.macAddress }.distinct().size != devices.size) null
            else BoundDeviceList(total, devices)
        } catch (_: JSONException) {
            null
        }
    }

    fun canonicalMac(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.matches(Regex("(?:[a-fA-F0-9]{12}|(?:[a-fA-F0-9]{2}:){5}[a-fA-F0-9]{2}|(?:[a-fA-F0-9]{2}-){5}[a-fA-F0-9]{2})"))) return null
        return trimmed.replace(":", "").replace("-", "").uppercase(java.util.Locale.ROOT)
    }

    fun parseUnbindToken(page: String): String? = Regex(
        """ajaxCsrfToken=["']?\s*\+\s*['"]([a-zA-Z0-9-]+)['"]"""
    ).find(page)?.groupValues?.get(1)

    fun parseBindResponse(response: String): SeamlessActionResult = try {
        val data = JSONObject(response)
        when (data.optString("state")) {
            "success" -> SeamlessActionResult.Success
            "fail" -> SeamlessActionResult.Failure(data.optString("data").trim())
            else -> SeamlessActionResult.Indeterminate("")
        }
    } catch (_: JSONException) {
        SeamlessActionResult.Indeterminate("")
    }
}
