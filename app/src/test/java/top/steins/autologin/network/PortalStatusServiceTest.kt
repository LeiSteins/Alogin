package top.steins.autologin.network

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalStatusServiceTest {
    private val dynamicPage = """
        <meta http-equiv="Content-Type" content="text/html; charset=gb2312">
        <title>上网登录页</title><!--Dr.COMWebLoginID_0.htm-->
        <script>v46ip='10.1.2.3';vlanid="0";ss4='000000000000';</script>
        <script src="a41.js?version=123"></script><script>page.run();</script>
    """.trimIndent()

    @Test
    fun fetchLoginStatus_usesConfiguredOnlineListInsteadOfTemplateTitleOrChkstatus() = runTest {
        val requests = mutableListOf<Request>()
        val client = clientWithInterceptor { chain ->
            val request = chain.request()
            requests += request
            when (request.url.encodedPath) {
                "/" -> if (request.url.host == "lgn.bjut.edu.cn") {
                    response(chain, body = "<title>注销页</title><script>uid='2021002';</script>")
                } else {
                    response(chain, body = dynamicPage)
                }
                "/eportal/portal/page/loadConfig" -> response(chain, body = config("1"))
                "/eportal/portal/online_list" -> response(
                    chain, body = """dr1003({"result":1,"msg":"","list":[{"user_account":"2021002"}],"total":1});"""
                )
                // 该接口在已登录环境也可能返回 0，配置要求使用 online_list 时不得请求它。
                "/drcom/chkstatus" -> response(chain, body = """dr1003({"result":0});""")
                else -> throw IOException("Unexpected URL")
            }
        }

        val result = fetchLoginStatus(client, wlanUserIp = "10.1.2.4")

        assertEquals(LoginStatus(isLoggedIn = true, uid = "2021002"), result)
        assertEquals(
            listOf("/", "/eportal/portal/page/loadConfig", "/eportal/portal/online_list", "/"),
            requests.map { it.url.encodedPath }
        )
        val configRequest = requests[1]
        assertEquals(801, configRequest.url.port)
        for (field in listOf("wlan_user_ipv6", "wlan_user_ssid", "wlan_user_areaid", "wlan_ac_ip", "wlan_ap_mac", "gw_id")) {
            assertEquals("", configRequest.url.queryParameter(field))
        }
        assertEquals(
            Base64.getEncoder().encodeToString("10.1.2.4".toByteArray()),
            configRequest.url.queryParameter("wlan_user_ip")
        )
        val statusRequest = requests[2]
        assertEquals("167838212", statusRequest.url.queryParameter("wlan_user_ip"))
        assertEquals("167838212", statusRequest.url.queryParameter("curr_user_ip"))
        assertEquals("", statusRequest.url.queryParameter("user_account"))
        assertEquals("", statusRequest.url.queryParameter("user_password"))
        assertEquals("no-cache", statusRequest.header("Cache-Control"))
        assertEquals("lgn.bjut.edu.cn", requests.last().url.host)
    }

    @Test
    fun fetchLoginStatus_skipsLgnWhenConfiguredStatusConfirmsOffline() = runTest {
        val paths = mutableListOf<String>()
        val client = dynamicClient(paths, statusBody = """dr1003({"result":0});""")

        assertEquals(LoginStatus(isLoggedIn = false), fetchLoginStatus(client))
        assertEquals(listOf("/", "/eportal/portal/page/loadConfig", "/eportal/portal/online_list"), paths)
    }

    @Test
    fun recheckLoginStatus_usesChkstatusOnlyWhenConfigured() = runTest {
        val paths = mutableListOf<String>()
        val client = dynamicClient(paths, configBody = config("0"))

        assertEquals(LoginRecheckResult.LoggedIn, recheckLoginStatus(client))
        assertEquals(listOf("/", "/eportal/portal/page/loadConfig", "/drcom/chkstatus"), paths)
    }

    @Test
    fun recheckLoginStatus_loadsWlgnConfigFromHttps802AndKernelStatusFrom443() = runTest {
        val requests = mutableListOf<Request>()
        val client = clientWithInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != "wlgn.bjut.edu.cn") throw IOException("unavailable")
            requests += request
            when (request.url.encodedPath) {
                "/a79.htm" -> response(chain, body = dynamicPage)
                "/eportal/portal/page/loadConfig" -> {
                    assertEquals(802, request.url.port)
                    response(chain, body = config("0"))
                }
                "/drcom/chkstatus" -> {
                    assertEquals(443, request.url.port)
                    response(chain, body = """dr1003({"result":1});""")
                }
                else -> throw AssertionError("Unexpected path")
            }
        }

        assertEquals(LoginRecheckResult.LoggedIn, recheckLoginStatus(client, wlanUserIp = "10.1.2.4"))
        assertEquals(
            listOf("/a79.htm", "/eportal/portal/page/loadConfig", "/drcom/chkstatus"),
            requests.map { it.url.encodedPath }
        )
        assertTrue(requests.all { it.url.isHttps })
    }

    @Test
    fun recheckLoginStatus_usesHttps802ForWlgnRadiusStatusWhenConfigured() = runTest {
        val requests = mutableListOf<Request>()
        val client = clientWithInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != "wlgn.bjut.edu.cn") throw IOException("unavailable")
            requests += request
            when (request.url.encodedPath) {
                "/a79.htm" -> response(chain, body = dynamicPage)
                "/eportal/portal/page/loadConfig" -> response(chain, body = config("1"))
                "/eportal/portal/online_list" -> response(chain, body = """dr1003({"result":1});""")
                else -> throw AssertionError("Unexpected path")
            }
        }

        assertEquals(LoginRecheckResult.LoggedIn, recheckLoginStatus(client, wlanUserIp = "10.1.2.4"))
        assertEquals(listOf(443, 802, 802), requests.map { it.url.port })
        assertEquals(
            listOf("/a79.htm", "/eportal/portal/page/loadConfig", "/eportal/portal/online_list"),
            requests.map { it.url.encodedPath }
        )
        assertTrue(requests.all { it.url.isHttps })
    }

    @Test
    fun fetchLoginStatus_returnsUnknownWhenPortalConfigurationCannotBeRead() = runTest {
        for (configBody in listOf("<html>unavailable</html>", "{}", config("2"), "dr1003({")) {
            val paths = mutableListOf<String>()
            val client = dynamicClient(paths, configBody = configBody)

            assertNull(fetchLoginStatus(client))
            assertEquals(listOf("/", "/eportal/portal/page/loadConfig"), paths)
        }
    }

    @Test
    fun fetchLoginStatus_returnsUnknownWhenPortalStateIsMalformedOrUnavailable() = runTest {
        for (statusBody in listOf("<title>上网登录页</title>", "{}", """{"result":2}""", """{"result":false}""")) {
            val paths = mutableListOf<String>()
            val client = dynamicClient(paths, statusBody = statusBody)

            assertNull(fetchLoginStatus(client))
            assertEquals(listOf("/", "/eportal/portal/page/loadConfig", "/eportal/portal/online_list"), paths)
        }
        val paths = mutableListOf<String>()
        assertNull(fetchLoginStatus(dynamicClient(paths, statusCode = 503)))
    }

    @Test
    fun recheckLoginStatus_doesNotUseTemplateTitleAfterStatusRequestFailure() = runTest {
        val paths = mutableListOf<String>()
        val client = clientWithInterceptor { chain ->
            if (chain.request().url.host != "10.21.221.98") throw IOException("unavailable")
            paths += chain.request().url.encodedPath
            if (chain.request().url.encodedPath == "/") response(chain, body = dynamicPage)
            else throw IOException("unavailable")
        }

        assertEquals(LoginRecheckResult.Unknown, recheckLoginStatus(client))
        assertEquals(listOf("/", "/eportal/portal/page/loadConfig"), paths)
    }

    @Test
    fun recheckLoginStatus_keepsUnknownWhenDynamicQueryFailsAndOtherPortalShowsLoginTitle() = runTest {
        val client = clientWithInterceptor { chain ->
            when {
                chain.request().url.host == "wlgn.bjut.edu.cn" ->
                    response(chain, body = "<title>上网登录页</title>")
                chain.request().url.encodedPath == "/" -> response(chain, body = dynamicPage)
                else -> response(chain, code = 503, body = "unavailable")
            }
        }

        assertEquals(LoginRecheckResult.Unknown, recheckLoginStatus(client))
    }

    @Test
    fun recheckLoginStatus_doesNotQueryAnInvalidIp() = runTest {
        val paths = mutableListOf<String>()
        val client = dynamicClient(paths)

        assertEquals(LoginRecheckResult.Unknown, recheckLoginStatus(client, wlanUserIp = "10.1.2.999"))
        assertEquals(listOf("/"), paths)
    }

    @Test
    fun isDynamicCampusPortal_recognizesScriptAndInitialization() {
        assertTrue(dynamicPage.toByteArray().isDynamicCampusPortal())
        assertTrue("<script>page . run ();</script>".toByteArray().isDynamicCampusPortal())
        assertFalse("<title>上网登录页</title>".toByteArray().isDynamicCampusPortal())
    }

    private fun config(method: String): String =
        """dr1003({"data":{"check_online_method":"$method","login_method":"1"}});"""

    private fun dynamicClient(
        paths: MutableList<String>,
        configBody: String = config("1"),
        statusBody: String = """dr1003({"result":1});""",
        statusCode: Int = 200
    ): OkHttpClient = clientWithInterceptor { chain ->
        if (chain.request().url.host != "10.21.221.98") throw IOException("unavailable")
        val path = chain.request().url.encodedPath
        paths += path
        when (path) {
            "/" -> response(chain, body = dynamicPage)
            "/eportal/portal/page/loadConfig" -> response(chain, body = configBody)
            else -> response(chain, code = statusCode, body = statusBody)
        }
    }

    private fun clientWithInterceptor(block: (Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(Interceptor(block))
            .build()

    private fun response(chain: Interceptor.Chain, code: Int = 200, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody())
            .build()
}
