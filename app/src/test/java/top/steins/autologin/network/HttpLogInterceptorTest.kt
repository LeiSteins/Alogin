package top.steins.autologin.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.steins.autologin.R
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class HttpLogInterceptorTest {
    private val logStore = HttpLogStorage()

    @Before
    fun setUp() {
        logStore.setEnabled(true)
    }

    private val messages = HttpLogMessageProvider { resId, formatArgs ->
        when (resId) {
            R.string.log_response_body_truncated ->
                "…(响应体超过 ${formatArgs[0]} KiB，已截断)"

            else -> "placeholder"
        }
    }

    @Test
    fun interceptor_hidesDeviceTokensAndBodiesWithoutChangingActualTraffic() {
        val responseBody = """{"total":1,"rows":[["1","AABBCC112233","phone","",""]]}"""
        CapturingTestServer(responseBody.toByteArray(StandardCharsets.UTF_8)).use { server ->
            val client = OkHttpClient.Builder().addInterceptor(HttpLogInterceptor(messages, logStore)).build()
            client.newCall(Request.Builder()
                .url("http://127.0.0.1:${server.port}/Self/service/bindMac?ajaxCsrfToken=private-token&mac=AABBCC112233")
                .post("macAddress=AABBCC112233&ajaxCsrfToken=private-token".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()).execute().use { assertEquals(responseBody, it.body?.string()) }
            server.awaitRequest()
            val log = logStore.logs.value.single()
            assertFalse(log.url.contains("private-token"))
            assertFalse(log.url.contains("AABBCC112233"))
            assertFalse(log.requestBody.contains("private-token"))
            assertFalse(log.requestBody.contains("AABBCC112233"))
            assertFalse(log.responseBody.contains("AABBCC112233"))
        }
    }

    @Test
    fun interceptor_whenDisabledPassesThroughWithoutRecording() {
        CapturingTestServer("ok".toByteArray(StandardCharsets.UTF_8)).use { server ->
            logStore.setEnabled(false)
            val client = OkHttpClient.Builder()
                .addInterceptor(HttpLogInterceptor(messages, logStore))
                .build()

            client.newCall(
                Request.Builder().url("http://127.0.0.1:${server.port}/status").build()
            ).execute().use {
                assertEquals("ok", it.body?.string())
            }
            server.awaitRequest()

            assertTrue(logStore.logs.value.isEmpty())
        }
    }

    @Test
    fun interceptor_decodesGb2312AndGbkResponsesWithoutChangingBusinessBytes() {
        for (charsetName in listOf("GB2312", "GBK")) {
            val text = "<title>上网登录页</title><p>校园网账号信息</p>"
            assertLoggedResponse(text, text.toByteArray(Charset.forName(charsetName)), "text/html; charset=$charsetName")
        }
    }

    @Test
    fun interceptor_usesHtmlMetaCharsetWhenHttpHeaderOmitsIt() {
        for (meta in listOf(
            "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=gb2312\">",
            "<meta charset='GB2312'>"
        )) {
            val text = "<html><head>$meta<title>注销页</title></head></html>"
            assertLoggedResponse(text, text.toByteArray(Charset.forName("GB2312")), "text/html")
        }
    }

    @Test
    fun interceptor_prefersHttpCharsetOverConflictingHtmlMeta() {
        val text = "<meta charset='UTF-8'><title>上网登录页</title>"
        assertLoggedResponse(text, text.toByteArray(Charset.forName("GBK")), "text/html; charset=gbk")
    }

    @Test
    fun interceptor_defaultsToUtf8WhenCharsetIsMissingOrUnsupported() {
        val text = "登录成功，正在获取账号信息"
        for (contentType in listOf("text/plain", "text/plain; charset=unsupported-charset")) {
            assertLoggedResponse(text, text.toByteArray(StandardCharsets.UTF_8), contentType)
        }
        val html = "<meta charset='unsupported-charset'><title>注销页</title>"
        assertLoggedResponse(html, html.toByteArray(StandardCharsets.UTF_8), "text/html")
    }

    private fun assertLoggedResponse(text: String, bytes: ByteArray, contentType: String) {
        logStore.clear()
        CapturingTestServer(bytes, contentType).use { server ->
            val client = OkHttpClient.Builder()
                .addInterceptor(HttpLogInterceptor(messages, logStore))
                .build()
            client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/status").build())
                .execute().use { response ->
                    assertArrayEquals(bytes, response.body?.bytes())
                }
            server.awaitRequest()
            assertEquals(text, logStore.logs.value.single().responseBody)
        }
    }

    @Test
    fun interceptor_keepsFullRequestAndResponseAvailableToBusinessCode() {
        val requestBody = "request=" + "x".repeat(128)
        val responseBody = "response=" + "y".repeat(4_096)
        val receivedRequestBody = AtomicReference<String>()
        val serverFailure = AtomicReference<Throwable?>()

        ServerSocket(0).use { server ->
            val serverThread = thread(start = true) {
                try {
                    server.accept().use { socket ->
                        val (headers, body) = socket.getInputStream().readHttpRequest()
                        val contentLength = Regex("""Content-Length: (\d+)""", RegexOption.IGNORE_CASE)
                            .find(headers)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt()
                            ?: 0
                        receivedRequestBody.set(body.copyOf(contentLength).toString(StandardCharsets.UTF_8))

                        val responseBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                        socket.getOutputStream().use { output ->
                            output.write(
                                (
                                        "HTTP/1.1 200 OK\r\n" +
                                                "Content-Type: text/plain; charset=utf-8\r\n" +
                                                "Content-Length: ${responseBytes.size}\r\n" +
                                                "Connection: close\r\n\r\n"
                                        ).toByteArray(StandardCharsets.UTF_8)
                            )
                            output.write(responseBytes)
                        }
                    }
                } catch (error: Throwable) {
                    serverFailure.set(error)
                }
            }

            logStore.clear()
            try {
                val client = OkHttpClient.Builder()
                    .addInterceptor(HttpLogInterceptor(messages, logStore))
                    .build()
                val response = client.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:${server.localPort}/login")
                        .post(requestBody.toRequestBody("text/plain".toMediaType()))
                        .build()
                ).execute()

                response.use {
                    assertEquals(responseBody, it.body?.string())
                }
                serverThread.join()

                assertNull(serverFailure.get())
                assertEquals(requestBody, receivedRequestBody.get())
                val log = logStore.logs.value.single()
                assertEquals(requestBody, log.requestBody)
                assertEquals(responseBody, log.responseBody)
            } finally {
                logStore.clear()
            }
        }
    }

    @Test
    fun interceptor_redactsSensitiveQueryParamsInLogOnly() {
        CapturingTestServer("ok".toByteArray(StandardCharsets.UTF_8)).use { server ->
            logStore.clear()
            try {
                val client = OkHttpClient.Builder()
                    .addInterceptor(HttpLogInterceptor(messages, logStore))
                    .build()
                val url = "http://127.0.0.1:${server.port}/login" +
                        "?callback=dr1003" +
                        "&upass=secret123" +
                        "&user_password=secret456" +
                        "&user_account=2021001" +
                        "&wlan_user_ip=10.1.2.3" +
                        "&lang=zh"
                client.newCall(
                    Request.Builder().url(url).get().build()
                ).execute().use {
                    assertEquals("ok", it.body?.string())
                }

                val rawRequest = server.awaitRequest()
                // 实际发出的请求保持不变，敏感参数仍然原样出现在线路上。
                assertTrue(rawRequest.contains("upass=secret123"))
                assertTrue(rawRequest.contains("user_password=secret456"))

                val log = logStore.logs.value.single()
                assertTrue(log.url.contains("upass=***"))
                assertTrue(log.url.contains("user_password=***"))
                assertTrue(log.url.contains("user_account=***"))
                assertTrue(log.url.contains("wlan_user_ip=***"))
                assertFalse(log.url.contains("secret123"))
                assertFalse(log.url.contains("secret456"))
                assertTrue(log.url.contains("callback=dr1003"))
                assertTrue(log.url.contains("lang=zh"))
            } finally {
                logStore.clear()
            }
        }
    }

    @Test
    fun interceptor_truncatesLargeResponseBodyInLogButKeepsFullBodyForBusiness() {
        val responseBody = "z".repeat(200_000)
        CapturingTestServer(responseBody.toByteArray(StandardCharsets.UTF_8)).use { server ->
            logStore.clear()
            try {
                val client = OkHttpClient.Builder()
                    .addInterceptor(HttpLogInterceptor(messages, logStore))
                    .build()
                val response = client.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:${server.port}/large")
                        .get()
                        .build()
                ).execute()

                response.use {
                    assertEquals(responseBody, it.body?.string())
                }
                server.awaitRequest()

                val log = logStore.logs.value.single()
                assertTrue(log.responseBody.contains("已截断"))
                assertTrue(log.responseBody.length < responseBody.length)
            } finally {
                logStore.clear()
            }
        }
    }
}

private class CapturingTestServer(
    responseBody: ByteArray,
    contentType: String = "text/plain; charset=utf-8"
) : AutoCloseable {
    private val server = ServerSocket(0)
    private val capturedRequest = AtomicReference<String>()
    private val serverFailure = AtomicReference<Throwable?>()

    val port: Int get() = server.localPort

    private val serverThread = thread(start = true) {
        try {
            server.accept().use { socket ->
                val (headers, body) = socket.getInputStream().readHttpRequest()
                capturedRequest.set(headers + body.toString(StandardCharsets.UTF_8))

                socket.getOutputStream().use { output ->
                    output.write(
                        (
                                "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: $contentType\r\n" +
                                        "Content-Length: ${responseBody.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                ).toByteArray(StandardCharsets.UTF_8)
                    )
                    output.write(responseBody)
                }
            }
        } catch (error: Throwable) {
            serverFailure.set(error)
        }
    }

    fun awaitRequest(): String {
        serverThread.join()
        assertNull(serverFailure.get())
        return capturedRequest.get()
    }

    override fun close() {
        server.close()
    }
}

private fun java.io.InputStream.readHttpRequest(): Pair<String, ByteArray> {
    val headerBytes = ByteArrayOutputStream()
    var previous = 0
    var previousPrevious = 0
    var previousPreviousPrevious = 0
    while (true) {
        val next = read()
        check(next >= 0) { "连接在 HTTP 头结束前关闭" }
        headerBytes.write(next)
        if (previousPreviousPrevious == '\r'.code && previousPrevious == '\n'.code &&
            previous == '\r'.code && next == '\n'.code
        ) {
            break
        }
        previousPreviousPrevious = previousPrevious
        previousPrevious = previous
        previous = next
    }

    val headers = headerBytes.toString(StandardCharsets.UTF_8)
    val contentLength = Regex("""Content-Length: (\d+)""", RegexOption.IGNORE_CASE)
        .find(headers)
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: 0
    return headers to readNBytes(contentLength)
}
