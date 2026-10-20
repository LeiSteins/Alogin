package top.steins.autologin.network

import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import top.steins.autologin.R
import top.steins.autologin.domain.model.BoundDevice
import top.steins.autologin.domain.model.BoundDeviceList
import top.steins.autologin.domain.model.SEAMLESS_DEVICE_LIMIT
import top.steins.autologin.domain.model.SeamlessAction
import top.steins.autologin.domain.model.SeamlessActionResult
import top.steins.autologin.domain.model.SeamlessStatus

/** 在调用方持有自助服务会话锁期间执行；HTTP 传输可替换，便于验证真实请求顺序。 */
internal class SeamlessService(
    private val execute: suspend (Request) -> String,
    private val isCurrent: () -> Boolean,
    private val message: (Int) -> String,
    private val userAgent: String
) {
    suspend fun perform(action: SeamlessAction): SeamlessActionResult {
        var submitted = false
        return try {
            when (action) {
                is SeamlessAction.Toggle -> {
                    val current = readStatus()
                    if (current != action.expectedStatus || current == SeamlessStatus.Unknown) {
                        return SeamlessActionResult.Failure(message(R.string.seamless_status_changed))
                    }
                    if (!isCurrent()) throw SeamlessProtocolException(message(R.string.seamless_session_expired))
                    submitted = true
                    execute(seamlessRequest("dashboard/oprateMauthAction").build())
                    if (readStatus() != current) SeamlessActionResult.Success
                    else SeamlessActionResult.Indeterminate(message(R.string.seamless_indeterminate))
                }
                is SeamlessAction.Bind, is SeamlessAction.Unbind -> {
                    val input = when (action) {
                        is SeamlessAction.Bind -> action.macAddress
                        is SeamlessAction.Unbind -> action.macAddress
                        else -> error("Unexpected action")
                    }
                    val mac = SeamlessParsing.canonicalMac(input)
                        ?: return SeamlessActionResult.Failure(message(R.string.seamless_invalid_mac))
                    val before = readDevices()
                    val exists = before.devices.any { SeamlessParsing.canonicalMac(it.macAddress) == mac }
                    if (action is SeamlessAction.Bind && exists) {
                        return SeamlessActionResult.Failure(message(R.string.seamless_duplicate_mac))
                    }
                    if (action is SeamlessAction.Bind && before.total >= SEAMLESS_DEVICE_LIMIT && !action.allowReplacement) {
                        return SeamlessActionResult.Failure(message(R.string.seamless_replacement_required))
                    }
                    if (action is SeamlessAction.Unbind && !exists) {
                        return SeamlessActionResult.Failure(message(R.string.seamless_device_changed))
                    }
                    // 三台时由服务端替换最后一台，客户端不预先删除已有设备。
                    val page = execute(seamlessRequest("service/myMac").build())
                    val token = SeamlessParsing.parseUnbindToken(page)
                        ?: throw SeamlessProtocolException(message(R.string.seamless_token_missing))
                    if (!isCurrent()) throw SeamlessProtocolException(message(R.string.seamless_session_expired))
                    if (action is SeamlessAction.Bind) {
                        submitted = true
                        val body = execute(seamlessRequest("service/bindMac")
                            .post(FormBody.Builder().add("macAddress", mac).add("macType", "0")
                                .add("ajaxCsrfToken", token).build()).build())
                        when (val result = SeamlessParsing.parseBindResponse(body)) {
                            is SeamlessActionResult.Failure -> return SeamlessActionResult.Failure(
                                result.message.ifBlank { message(R.string.seamless_operation_failed) }
                            )
                            else -> Unit
                        }
                    } else {
                        val url = selfServiceUrl("service/unbindmac").newBuilder()
                            .addQueryParameter("mac", mac).addQueryParameter("ajaxCsrfToken", token).build()
                        submitted = true
                        execute(seamlessRequest("service/unbindmac").url(url).build())
                    }
                    val after = readDevices()
                    val nowExists = after.devices.any { SeamlessParsing.canonicalMac(it.macAddress) == mac }
                    if (nowExists == (action is SeamlessAction.Bind)) SeamlessActionResult.Success
                    else SeamlessActionResult.Indeterminate(message(R.string.seamless_indeterminate))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (submitted) SeamlessActionResult.Indeterminate(message(R.string.seamless_indeterminate))
            else SeamlessActionResult.Failure((error as? SeamlessProtocolException)?.message ?: message(R.string.seamless_operation_failed))
        }
    }

    private fun selfServiceUrl(path: String): HttpUrl = HttpUrl.Builder()
        .scheme("https").host("jfself.bjut.edu.cn").addPathSegments("Self/$path").build()

    private fun seamlessRequest(path: String): Request.Builder = Request.Builder()
        .url(selfServiceUrl(path)).header("User-Agent", userAgent)
        .header("Cache-Control", "no-cache")
        .header("Referer", "https://jfself.bjut.edu.cn/Self/service/myMac")

    suspend fun readStatus(): SeamlessStatus {
        val body = execute(seamlessRequest("dashboard/refreshMauthType")
            .header("X-Requested-With", "XMLHttpRequest").build())
        return SeamlessParsing.parseStatus(body).takeIf { it != SeamlessStatus.Unknown }
            ?: throw SeamlessProtocolException(message(R.string.seamless_status_failed))
    }

    suspend fun readDevices(): BoundDeviceList {
        val devices = mutableListOf<BoundDevice>()
        var total: Int? = null
        var page = 1
        do {
            val url = selfServiceUrl("service/getMacList").newBuilder()
                .addQueryParameter("pageSize", "10").addQueryParameter("pageNumber", page.toString())
                .addQueryParameter("searchText", "").addQueryParameter("sortName", "2")
                .addQueryParameter("sortOrder", "DESC").build()
            val body = execute(seamlessRequest("service/getMacList").url(url)
                .header("X-Requested-With", "XMLHttpRequest").build())
            val result = SeamlessParsing.parseDevices(body)
                ?: throw SeamlessProtocolException(message(R.string.seamless_devices_failed))
            if ((total != null && total != result.total) || result.total > 100 ||
                (result.devices.isEmpty() && devices.size < result.total)
            ) throw SeamlessProtocolException(message(R.string.seamless_devices_failed))
            total = result.total
            devices += result.devices
            page += 1
        } while (devices.size < total)
        if (devices.size != total || devices.map { it.macAddress }.distinct().size != devices.size) {
            throw SeamlessProtocolException(message(R.string.seamless_devices_failed))
        }
        return BoundDeviceList(total, devices)
    }

}

internal class SeamlessProtocolException(message: String) : Exception(message)
