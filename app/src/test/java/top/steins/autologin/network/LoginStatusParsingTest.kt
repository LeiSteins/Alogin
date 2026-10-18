package top.steins.autologin.network

import java.io.IOException
import java.nio.charset.Charset
import kotlinx.coroutines.test.runTest
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginStatusParsingTest {

    @Test
    fun fetchLoginStatus_skipsLgnWhenGatewayShowsLoginPage() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            requestedUrls += chain.request().url.toString()
            response(chain, body = "<title>上网登录页</title>")
        }

        val result = fetchLoginStatus(client)

        assertEquals(LoginStatus(isLoggedIn = false), result)
        assertEquals(listOf("http://10.21.221.98/"), requestedUrls)
    }

    @Test
    fun fetchLoginStatus_skipsLgnWhenFallbackPortalShowsLoginPage() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            requestedUrls += chain.request().url.toString()
            when (chain.request().url.host) {
                "10.21.221.98" -> throw IOException("unavailable")
                else -> response(chain, body = "<title>上网登录页</title>")
            }
        }

        val result = fetchLoginStatus(client)

        assertEquals(LoginStatus(isLoggedIn = false), result)
        assertEquals(
            listOf("http://10.21.221.98/", "https://wlgn.bjut.edu.cn/a79.htm"),
            requestedUrls
        )
    }

    @Test
    fun fetchLoginStatus_loadsAccountDetailsOnlyAfterPortalConfirmsLogin() = runTest {
        val requestedHosts = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            when (host) {
                "10.21.221.98" -> response(
                    chain, code = 302, headers = mapOf("Location" to "https://lgn.bjut.edu.cn/")
                )
                else -> response(
                    chain,
                    body = "<title>注销页</title><script>uid='2021001';" +
                        "flow='1024';time='60';v4ip='10.0.0.1';</script>"
                )
            }
        }

        val result = fetchLoginStatus(client)

        assertEquals(
            LoginStatus(true, uid = "2021001", flow = "1024", time = "60", v4ip = "10.0.0.1"),
            result
        )
        assertEquals(listOf("10.21.221.98", "lgn.bjut.edu.cn"), requestedHosts)
    }

    @Test
    fun fetchLoginStatus_keepsConfirmedLoginWhenLgnIsUnavailable() = runTest {
        val requestedHosts = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            when (host) {
                "10.21.221.98" -> response(chain, code = 503)
                "wlgn.bjut.edu.cn" -> response(chain, body = "<title>注销页</title>")
                else -> throw IOException("unavailable")
            }
        }

        val result = fetchLoginStatus(client)

        assertEquals(LoginStatus(isLoggedIn = true), result)
        assertEquals(listOf("10.21.221.98", "wlgn.bjut.edu.cn", "lgn.bjut.edu.cn"), requestedHosts)
    }

    @Test
    fun fetchLoginStatus_keepsConfirmedLoginWhenLgnReturnsInvalidDetails() = runTest {
        for ((code, body) in listOf(503 to "unavailable", 200 to "<title>上网登录页</title>")) {
            val requestedHosts = mutableListOf<String>()
            val client = clientWithInterceptor { chain ->
                val host = chain.request().url.host
                requestedHosts += host
                when (host) {
                    "10.21.221.98" -> response(
                        chain, code = 302, headers = mapOf("Location" to "https://lgn.bjut.edu.cn/")
                    )
                    else -> response(chain, code = code, body = body)
                }
            }

            assertEquals(LoginStatus(isLoggedIn = true), fetchLoginStatus(client))
            assertEquals(listOf("10.21.221.98", "lgn.bjut.edu.cn"), requestedHosts)
        }
    }

    @Test
    fun fetchLoginStatus_returnsUnknownWithoutLgnWhenBothPortalsCannotVerifyLogin() = runTest {
        for (unavailable in listOf(true, false)) {
            val requestedHosts = mutableListOf<String>()
            val client = clientWithInterceptor { chain ->
                requestedHosts += chain.request().url.host
                if (unavailable) throw IOException("unavailable")
                response(chain, body = "<title>unknown</title>")
            }

            assertNull(fetchLoginStatus(client))
            assertEquals(listOf("10.21.221.98", "wlgn.bjut.edu.cn"), requestedHosts)
        }
    }

    @Test
    fun recheckLoginStatus_rejectsRedirectToDifferentHostWithLgnPrefix() = runTest {
        val client = clientWithInterceptor { chain ->
            response(
                chain, code = 302,
                headers = mapOf("Location" to "https://lgn.bjut.edu.cn.example.com/")
            )
        }

        assertEquals(LoginRecheckResult.Unknown, recheckLoginStatus(client))
    }

    @Test
    fun recheckLoginStatus_confirmsLoggedInStateFromLoginPageRedirect() = runTest {
        val requestedHosts = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            when (host) {
                "10.21.221.98" -> response(
                    chain = chain,
                    code = 302,
                    headers = mapOf("Location" to "https://lgn.bjut.edu.cn/")
                )
                else -> throw AssertionError("Unexpected host: $host")
            }
        }

        val result = recheckLoginStatus(client)

        assertEquals(LoginRecheckResult.LoggedIn, result)
        assertEquals(listOf("10.21.221.98"), requestedHosts)
    }

    @Test
    fun recheckLoginStatus_confirmsLoggedOutStateFromLoginPage() = runTest {
        val loginPage = "<html><head><title>上网登录页</title></head></html>"
        val requestedHosts = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            when (host) {
                "10.21.221.98" -> response(chain, body = loginPage)
                else -> throw AssertionError("Unexpected host: $host")
            }
        }

        val result = recheckLoginStatus(client)

        assertEquals(LoginRecheckResult.LoggedOut, result)
        assertEquals(listOf("10.21.221.98"), requestedHosts)
    }

    @Test
    fun recheckLoginStatus_usesSecondLoginPageWhenFirstIsUnavailable() = runTest {
        val logoutPage = "<html><head><title>注销页</title></head></html>"
        val requestedHosts = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            when (host) {
                "10.21.221.98" -> throw IOException("unavailable")
                "wlgn.bjut.edu.cn" -> response(chain, body = logoutPage)
                else -> throw AssertionError("Unexpected host: $host")
            }
        }

        val result = recheckLoginStatus(client)

        assertEquals(LoginRecheckResult.LoggedIn, result)
        assertEquals(listOf("10.21.221.98", "wlgn.bjut.edu.cn"), requestedHosts)
    }

    @Test
    fun isCampusLoginPage_recognizesUtf8Title() {
        val body = "<html><head><title>上网登录页</title></head></html>"

        assertTrue(body.toByteArray(Charsets.UTF_8).isCampusLoginPage())
    }

    @Test
    fun isCampusLoginPage_recognizesGb2312Title() {
        val body = "<HTML><HEAD><TITLE>  上网登录页  </TITLE></HEAD></HTML>"

        assertTrue(body.toByteArray(Charset.forName("GB2312")).isCampusLoginPage())
    }

    @Test
    fun isCampusLoginPage_rejectsOtherPages() {
        val body = "<html><head><title>注销页</title></head></html>"

        assertFalse(body.toByteArray(Charsets.UTF_8).isCampusLoginPage())
    }

    private fun clientWithInterceptor(block: (Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(Interceptor(block))
            .build()

    private fun response(
        chain: Interceptor.Chain,
        code: Int = 200,
        body: String = "",
        headers: Map<String, String> = emptyMap()
    ): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .headers(headers.toHeaders())
        .body(body.toResponseBody())
        .build()
}
