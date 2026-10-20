package top.steins.autologin.network

import android.content.Context
import androidx.annotation.StringRes
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import top.steins.autologin.R
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

/** 日志文案解析器，解耦 Android Resources，便于 JVM 单元测试注入假实现。 */
fun interface HttpLogMessageProvider {
    fun get(@StringRes resId: Int, vararg formatArgs: Any): String
}

fun httpLogMessageProvider(context: Context): HttpLogMessageProvider =
    HttpLogMessageProvider { resId, formatArgs ->
        context.applicationContext.getString(resId, *formatArgs)
    }

class HttpLogInterceptor(
    private val messages: HttpLogMessageProvider,
    private val logSink: HttpLogSink
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!logSink.isEnabled) {
            return chain.proceed(chain.request())
        }

        val originalRequest = chain.request()
        val timestamp = System.currentTimeMillis()

        // 日志采集绝不能改变实际发出的请求或业务侧读取到的响应。
        val sensitiveSelfService = originalRequest.url.encodedPath.startsWith("/Self/service/")
        val requestBodyString = if (sensitiveSelfService && originalRequest.body != null) {
            messages.get(R.string.log_sensitive_body_omitted)
        } else captureRequestBody(originalRequest)
        val loggedUrl = redactSensitiveQueryParams(originalRequest.url)

        return try {
            val response = chain.proceed(originalRequest)
            val responseBodyString = if (sensitiveSelfService) {
                messages.get(R.string.log_sensitive_body_omitted)
            } else captureResponseBody(response)

            logSink.add(
                HttpLogEntry(
                    id = System.nanoTime(),
                    method = originalRequest.method,
                    url = loggedUrl,
                    statusCode = response.code,
                    timestamp = timestamp,
                    requestBody = requestBodyString,
                    responseBody = responseBodyString,
                    error = null
                )
            )

            response
        } catch (e: IOException) {
            logSink.add(
                HttpLogEntry(
                    id = System.nanoTime(),
                    method = originalRequest.method,
                    url = loggedUrl,
                    statusCode = 0,
                    timestamp = timestamp,
                    requestBody = requestBodyString,
                    responseBody = "",
                    error = e.message
                        ?: messages.get(R.string.log_unknown_network_error)
                )
            )
            throw e
        }
    }

    private fun captureRequestBody(request: Request): String {
        val body = request.body ?: return ""
        if (body.isDuplex() || body.isOneShot()) {
            return messages.get(R.string.log_request_body_not_replayable)
        }

        return try {
            val contentLength = body.contentLength()
            if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
                return messages.get(R.string.log_request_body_too_large)
            }

            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        } catch (e: Exception) {
            messages.get(R.string.log_request_body_read_failed, e.message.orEmpty())
        }
    }

    private fun captureResponseBody(response: Response): String = try {
        val bytes = response.peekBody(MAX_RESPONSE_BODY_BYTES + 1).bytes()
        val charset = response.body?.contentType()?.charset()
            ?: htmlCharset(bytes)
            ?: StandardCharsets.UTF_8
        val text = bytes.toString(charset)
        if (bytes.size > MAX_RESPONSE_BODY_BYTES) {
            "$text\n" + messages.get(
                R.string.log_response_body_truncated,
                MAX_RESPONSE_BODY_BYTES / 1024
            )
        } else {
            text
        }
    } catch (e: Exception) {
        messages.get(R.string.log_response_body_read_failed, e.message.orEmpty())
    }

    private fun htmlCharset(bytes: ByteArray): Charset? {
        val prefix = String(bytes, 0, minOf(bytes.size, MAX_CHARSET_PROBE_BYTES), StandardCharsets.ISO_8859_1)
        val name = HTML_CHARSET_PATTERN.find(prefix)?.groupValues?.get(1) ?: return null
        return try {
            Charset.forName(name)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * 脱敏 URL 查询串中的敏感参数。仅改写日志副本，实际请求保持原样。
     */
    private fun redactSensitiveQueryParams(url: HttpUrl): String {
        val hasSensitiveParam = (0 until url.querySize).any { index ->
            url.queryParameterName(index)?.lowercase(Locale.ROOT) in SENSITIVE_QUERY_PARAMS
        }
        if (!hasSensitiveParam) return url.toString()

        val builder = url.newBuilder().query(null)
        for (index in 0 until url.querySize) {
            val name = url.queryParameterName(index)
            val value = if (name?.lowercase(Locale.ROOT) in SENSITIVE_QUERY_PARAMS) {
                REDACTED_VALUE
            } else {
                url.queryParameterValue(index)
            }
            builder.addQueryParameter(name, value)
        }
        return builder.build().toString()
    }

    companion object {
        private const val MAX_REQUEST_BODY_BYTES = 2_000L
        private const val MAX_RESPONSE_BODY_BYTES = 64L * 1024
        private const val MAX_CHARSET_PROBE_BYTES = 4 * 1024
        private const val REDACTED_VALUE = "***"
        private val HTML_CHARSET_PATTERN = Regex(
            """<meta\b[^>]*\bcharset\s*=\s*["']?\s*([a-zA-Z0-9._:-]+)""",
            RegexOption.IGNORE_CASE
        )

        // WLGN 使用 DDDDD/upass，Eportal 使用 user_account/user_password，
        // 自助服务 SSO 使用 XOR 编码的 user_account 与 wlan_user_ip。
        private val SENSITIVE_QUERY_PARAMS = setOf(
            "ddddd",
            "upass",
            "user_account",
            "user_password",
            "wlan_user_ip",
            "mac",
            "macaddress",
            "ajaxcsrftoken",
            "csrftoken"
        )
    }
}
