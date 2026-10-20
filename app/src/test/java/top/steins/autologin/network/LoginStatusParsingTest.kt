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
import top.steins.autologin.domain.model.LoginStatus
import top.steins.autologin.domain.model.WifiLoginMethod

class LoginStatusParsingTest {

    @Test
    fun fetchLoginStatus_loadsCurrentAccountDirectlyWhenLoginAlreadyConfirmed() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            requestedUrls += chain.request().url.toString()
            response(chain, body = "<title>注销页</title><script>uid='2021002';</script>")
        }

        val result = fetchLoginStatus(client, knownLoggedIn = true)

        assertEquals(LoginStatus(isLoggedIn = true, uid = "2021002"), result)
        assertEquals(listOf("https://lgn.bjut.edu.cn/"), requestedUrls)
    }

    @Test
    fun fetchLoginStatus_keepsKnownLoginWhenCurrentAccountCannotBeLoaded() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            requestedUrls += chain.request().url.toString()
            throw IOException("unavailable")
        }

        val result = fetchLoginStatus(client, knownLoggedIn = true)

        assertEquals(LoginStatus(isLoggedIn = true), result)
        assertEquals(listOf("https://lgn.bjut.edu.cn/"), requestedUrls)
    }

    @Test
    fun fetchLoginStatus_skipsLgnWhenGatewayShowsLoginPage() = runTest {
        val requestedUrls = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            requestedUrls += chain.request().url.toString()
            response(chain, body = "<title>上网登录页</title>")
        }

        val result = fetchLoginStatus(client)

        assertEquals(LoginStatus(false, verifiedMethod = WifiLoginMethod.EPORTAL), result)
        assertEquals(
            listOf("http://10.21.221.98/", "https://wlgn.bjut.edu.cn/a79.htm"),
            requestedUrls
        )
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

        assertEquals(LoginStatus(false, verifiedMethod = WifiLoginMethod.WLGN), result)
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
            LoginStatus(true, uid = "2021001", flow = "1024", time = "60", v4ip = "10.0.0.1",
                verifiedMethod = WifiLoginMethod.EPORTAL),
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

        assertEquals(LoginStatus(true, verifiedMethod = WifiLoginMethod.WLGN), result)
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

            assertEquals(
                LoginStatus(true, verifiedMethod = WifiLoginMethod.EPORTAL),
                fetchLoginStatus(client)
            )
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
    fun fetchLoginStatus_usesOnlyRememberedPortalForDefiniteOnlineOrOfflineState() = runTest {
        for (method in WifiLoginMethod.entries) {
            for (online in listOf(true, false)) {
                val hosts = mutableListOf<String>()
                val client = clientWithInterceptor { chain ->
                    hosts += chain.request().url.host
                    response(chain, body = if (online) "<title>注销页</title>" else "<title>上网登录页</title>")
                }

                val result = fetchLoginStatus(client, preferredMethod = method)

                assertEquals(LoginStatus(online, verifiedMethod = method), result)
                val portalHost = if (method == WifiLoginMethod.WLGN) "wlgn.bjut.edu.cn" else "10.21.221.98"
                assertEquals(if (online) listOf(portalHost, "lgn.bjut.edu.cn") else listOf(portalHost), hosts)
            }
        }
    }

    @Test
    fun fetchLoginStatus_fallsBackAndReportsNewMethodWhenRememberedPortalIsUnavailable() = runTest {
        for (method in WifiLoginMethod.entries) {
            for (online in listOf(true, false)) {
                val hosts = mutableListOf<String>()
                val preferredHost = if (method == WifiLoginMethod.WLGN) "wlgn.bjut.edu.cn" else "10.21.221.98"
                val fallbackHost = if (method == WifiLoginMethod.WLGN) "10.21.221.98" else "wlgn.bjut.edu.cn"
                val client = clientWithInterceptor { chain ->
                    hosts += chain.request().url.host
                    if (chain.request().url.host == preferredHost) throw IOException("unavailable")
                    response(chain, body = if (online) "<title>注销页</title>" else "<title>上网登录页</title>")
                }

                val result = fetchLoginStatus(client, preferredMethod = method)

                val fallback = WifiLoginMethod.entries.first { it != method }
                assertEquals(LoginStatus(online, verifiedMethod = fallback), result)
                val expected = listOf(preferredHost, fallbackHost) + if (online) listOf("lgn.bjut.edu.cn") else emptyList()
                assertEquals(expected, hosts)
            }
        }
    }

    @Test
    fun fetchLoginStatus_doesNotReportMethodWhenRememberedPortalAndFallbackAreUnknown() = runTest {
        val hosts = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            hosts += chain.request().url.host
            response(chain, body = "<title>其他页面</title>")
        }

        assertNull(fetchLoginStatus(client, preferredMethod = WifiLoginMethod.WLGN))
        assertEquals(listOf("wlgn.bjut.edu.cn", "10.21.221.98"), hosts)
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
            response(chain, body = loginPage)
        }

        val result = recheckLoginStatus(client)

        assertEquals(LoginRecheckResult.LoggedOut, result)
        assertEquals(listOf("10.21.221.98", "wlgn.bjut.edu.cn"), requestedHosts)
    }

    @Test
    fun recheckLoginStatus_checksWlgnLogoutTitleAfterGatewayLoginTitle() = runTest {
        val requestedHosts = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            val host = chain.request().url.host
            requestedHosts += host
            val title = if (host == "10.21.221.98") "上网登录页" else "注销页"
            response(chain, body = "<title>$title</title>")
        }

        assertEquals(LoginRecheckResult.LoggedIn, recheckLoginStatus(client))
        assertEquals(listOf("10.21.221.98", "wlgn.bjut.edu.cn"), requestedHosts)
    }

    @Test
    fun recheckLoginStatus_doesNotTreatScriptReferenceAsLogoutPageTitle() = runTest {
        val client = clientWithInterceptor { chain ->
            response(chain, body = "<title>其他页面</title><script src='Dr.COMWebLoginID_1.htm'></script>")
        }

        assertEquals(LoginRecheckResult.Unknown, recheckLoginStatus(client))
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

    @Test
    fun loginStatusFromPageTitle_recognizesLogoutTitleInBothEncodings() {
        val body = "<HTML><HEAD><TITLE>\n 注销页 \n</TITLE></HEAD></HTML>"

        for (charset in listOf(Charsets.UTF_8, Charset.forName("GB2312"))) {
            assertEquals(LoginRecheckResult.LoggedIn, body.toByteArray(charset).loginStatusFromPageTitle())
        }
    }

    @Test
    fun loginStatusFromPageTitle_doesNotUseTextOutsideTitle() {
        val body = "<title>其他页面</title><p>注销页 上网登录页</p>"

        assertEquals(LoginRecheckResult.Unknown, body.toByteArray().loginStatusFromPageTitle())
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
