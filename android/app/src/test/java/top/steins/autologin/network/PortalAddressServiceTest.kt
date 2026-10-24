package top.steins.autologin.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
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
import top.steins.autologin.AppStrings
import top.steins.autologin.R
import top.steins.autologin.domain.model.LoginResult
import top.steins.autologin.domain.model.WifiLoginMethod

class PortalAddressServiceTest {
    private val strings = AppStrings { id, _ -> id.toString() }

    @Test
    fun portalAddress_usesFirstValidServerFieldAndTrimsPadding() {
        for ((body, expected) in listOf(
            "v46ip=' 10.1.2.3 ';ss5='10.4.5.6';" to "10.1.2.3",
            "v46ip='::1';ss5=' 10.4.5.6 ';" to "10.4.5.6",
            "ss5='';v4ip='10.7.8.9';" to "10.7.8.9"
        )) {
            assertEquals(expected, portalPageIpv4(body))
        }
        assertEquals("10.1.2.3", portalStatusIpv4("""dr1003({"result":0,"v46ip":" 10.1.2.3 ","ss5":"10.4.5.6"});"""))
        assertEquals("10.4.5.6", portalStatusIpv4("""{"v46ip":123,"ss5":"10.4.5.6"}"""))
        assertEquals("10.7.8.9", portalStatusIpv4("""{"v46ip":"invalid","v4ip":"10.7.8.9"}"""))
    }

    @Test
    fun portalAddress_rejectsMalformedAndUnusableAddresses() {
        for (ip in listOf("", "0.0.0.0", "127.0.0.1", "169.254.1.2", "224.0.0.1", "255.255.255.255",
            "10.1.2.999", "10.1.2", "10.1.2.3&x=1", "10.1.2.3/24", "010.1.2.3", "+10.1.2.3", "::1")) {
            assertNull(ip, portalPageIpv4("v46ip='$ip';"))
            assertNull(ip, portalStatusIpv4("""{"v46ip":"$ip"}"""))
        }
        for (body in listOf("", "<html>error</html>", "dr1003({", "{}", """{"v46ip":true}""")) {
            assertNull(portalStatusIpv4(body))
        }
    }

    @Test
    fun eportalLogin_usesServerIpEvenWhenKernelResultIsOffline() = runTest {
        val requests = mutableListOf<Request>()
        val client = client { chain ->
            requests += chain.request()
            when (chain.request().url.encodedPath) {
                "/drcom/chkstatus" -> response(chain, """dr1003({"result":0,"v46ip":"10.1.2.3"});""")
                "/eportal/portal/login" -> response(chain, """{"result":1}""")
                else -> throw AssertionError("Unexpected request")
            }
        }
        assertEquals(LoginResult.Success, login(strings, client, "student", "test-password", WifiLoginMethod.EPORTAL) { true })
        assertEquals(listOf(80, 801), requests.map { it.url.port })
        assertTrue(requests.all { it.url.host == "10.21.221.98" })
        assertNull(requests.first().url.queryParameter("user_account"))
        assertNull(requests.first().url.queryParameter("user_password"))
        assertEquals("no-cache", requests.first().header("Cache-Control"))
        assertEquals("10.1.2.3", requests.last().url.queryParameter("wlan_user_ip"))
        assertEquals("student@campus", requests.last().url.queryParameter("user_account"))
    }

    @Test
    fun detectedEportalLogin_usesFreshServerIpInsteadOfDiscoveryPageIp() = runTest {
        val requests = mutableListOf<Request>()
        val client = client { chain ->
            requests += chain.request()
            when (chain.request().url.encodedPath) {
                "/" -> response(chain, "<script>v46ip='10.9.8.7';</script>")
                "/drcom/chkstatus" -> response(chain, """{"v46ip":"10.1.2.3"}""")
                "/eportal/portal/login" -> response(chain, """{"result":1}""")
                else -> throw AssertionError("Unexpected request")
            }
        }
        assertEquals(LoginResult.Success, login(strings, client, "student", "test-password") { true })
        assertEquals(listOf("/", "/drcom/chkstatus", "/eportal/portal/login"), requests.map { it.url.encodedPath })
        assertEquals("10.1.2.3", requests.last().url.queryParameter("wlan_user_ip"))
    }

    @Test
    fun eportalLogin_fallsBackToHomepageWithoutFollowingRedirects() = runTest {
        for (failure in listOf("io", "http", "redirect", "invalid")) {
            val paths = mutableListOf<String>()
            val client = client { chain ->
                val path = chain.request().url.encodedPath
                paths += path
                when (path) {
                    "/drcom/chkstatus" -> when (failure) {
                        "io" -> throw IOException("unavailable")
                        "http" -> response(chain, "error", 503)
                        "redirect" -> response(chain, "", 302).newBuilder()
                            .header("Location", "https://untrusted.example/").build()
                        else -> response(chain, """{"v46ip":"0.0.0.0"}""")
                    }
                    "/" -> response(chain, "<script>v46ip='';ss5=' 10.4.5.6 ';</script>")
                    "/eportal/portal/login" -> {
                        assertEquals("10.4.5.6", chain.request().url.queryParameter("wlan_user_ip"))
                        response(chain, """{"result":1}""")
                    }
                    else -> throw AssertionError("Unexpected request")
                }
            }
            assertEquals(LoginResult.Success, login(strings, client, "student", "test-password", WifiLoginMethod.EPORTAL) { true })
            assertEquals(listOf("/drcom/chkstatus", "/", "/eportal/portal/login"), paths)
        }
    }

    @Test
    fun eportalLogin_missingServerIpNeverSubmitsCredentials() = runTest {
        val paths = mutableListOf<String>()
        val client = client { chain ->
            paths += chain.request().url.encodedPath
            response(chain, "<html>No valid IP</html>")
        }
        val result = login(strings, client, "student", "test-password", WifiLoginMethod.EPORTAL) { true }
        assertEquals(LoginResult.Failure(strings.get(R.string.login_no_valid_campus_ip)), result)
        assertEquals(listOf("/drcom/chkstatus", "/"), paths)
    }

    @Test
    fun login_networkChangeDuringDiscoveryOrIpLookupStopsBeforeCredentialSubmission() = runTest {
        for (preferred in listOf(null, WifiLoginMethod.EPORTAL)) {
            val current = AtomicBoolean(true)
            val paths = mutableListOf<String>()
            val client = client { chain ->
                paths += chain.request().url.encodedPath
                current.set(false)
                response(chain, """{"v46ip":"10.1.2.3"}""")
            }
            val result = login(strings, client, "student", "test-password", preferred) { current.get() }
            assertEquals(LoginResult.NetworkError(strings.get(R.string.login_network_changed)), result)
            assertEquals(listOf(if (preferred == null) "/" else "/drcom/chkstatus"), paths)
        }
    }

    @Test
    fun eportalLogin_rechecksNetworkImmediatelyBeforeSubmittingCredentials() = runTest {
        val checks = AtomicInteger()
        val paths = mutableListOf<String>()
        val client = client { chain ->
            paths += chain.request().url.encodedPath
            response(chain, """{"v46ip":"10.1.2.3"}""")
        }
        val result = login(strings, client, "student", "test-password", WifiLoginMethod.EPORTAL) { checks.incrementAndGet() < 4 }
        assertEquals(LoginResult.NetworkError(strings.get(R.string.login_network_changed)), result)
        assertEquals(listOf("/drcom/chkstatus"), paths)
    }

    @Test
    fun login_networkChangeDuringSubmissionDiscardsSuccessfulResponse() = runTest {
        val current = AtomicBoolean(true)
        val client = client { chain ->
            if (chain.request().url.encodedPath.endsWith("/login")) current.set(false)
            response(chain, """{"result":1,"v46ip":"10.1.2.3"}""")
        }
        assertEquals(LoginResult.NetworkError(strings.get(R.string.login_network_changed)),
            login(strings, client, "student", "test-password", WifiLoginMethod.EPORTAL) { current.get() })
    }

    @Test
    fun wlgnLogin_doesNotRequestOrSubmitAnIp() = runTest {
        val requests = mutableListOf<Request>()
        val client = client { chain ->
            requests += chain.request()
            response(chain, """{"result":1}""")
        }
        assertEquals(LoginResult.Success, login(strings, client, "student", "test-password", WifiLoginMethod.WLGN) { true })
        assertEquals(1, requests.size)
        assertEquals("https://wlgn.bjut.edu.cn/drcom/login", requests.single().url.newBuilder().query(null).build().toString())
        assertNull(requests.single().url.queryParameter("wlan_user_ip"))
    }

    @Test
    fun login_preservesCancellationWithoutSendingRequests() = runTest {
        var sent = false
        val client = client { chain -> sent = true; response(chain, "") }
        try {
            login(strings, client, "student", "test-password", WifiLoginMethod.EPORTAL) { throw CancellationException("cancelled") }
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(sent)
        }
    }

    private fun client(block: (Interceptor.Chain) -> Response): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor(Interceptor(block))
        .build()

    private fun response(chain: Interceptor.Chain, body: String, code: Int = 200): Response = Response.Builder()
        .request(chain.request())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody())
        .build()
}
