package top.steins.autologin.network

import org.json.JSONException
import org.json.JSONObject

internal sealed interface DeviceLogoutResponse {
    data object Success : DeviceLogoutResponse
    data class Failure(val message: String?) : DeviceLogoutResponse
    data object Unknown : DeviceLogoutResponse
}

internal object DeviceLogoutResponseParser {

    fun parse(response: String): DeviceLogoutResponse {
        val data = try {
            JSONObject(response)
        } catch (_: JSONException) {
            return DeviceLogoutResponse.Unknown
        }
        val success = when (val value = data.opt("success")) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            else -> null
        }
        return when (success) {
            true -> DeviceLogoutResponse.Success
            false -> DeviceLogoutResponse.Failure(
                data.optString("msg").ifBlank {
                    data.optString("message").ifBlank { null }
                }
            )
            null -> DeviceLogoutResponse.Unknown
        }
    }
}
