package top.steins.autologin.network

import android.content.Context
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.steins.autologin.R
import top.steins.autologin.data.WifiLoginMethod
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

data class LoginStatus(
    val isLoggedIn: Boolean,
    val uid: String = "",
    val flow: String = "",
    val time: String = "",
    val v4ip: String = "",
    val error: String = "",
    val verifiedMethod: WifiLoginMethod? = null
)

sealed class LoginResult {
    data object Success : LoginResult()
    data object AlreadyOnline : LoginResult()
    data class Failure(val message: String) : LoginResult()
    data class NetworkError(val message: String) : LoginResult()
}

internal fun createLoginClient(context: Context, network: Network): OkHttpClient =
    OkHttpClient.Builder()
        .bindToCampusNetwork(network)
        .allowCampusCertificateErrors()
        .connectTimeout(CAMPUS_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(CAMPUS_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CAMPUS_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .addInterceptor(HttpLogInterceptor(httpLogMessageProvider(context.applicationContext)))
        .build()

internal suspend fun login(
    context: Context,
    client: OkHttpClient,
    username: String,
    password: String,
    wlanUserIp: String,
    preferredMethod: WifiLoginMethod? = null
): LoginResult =
    withContext(Dispatchers.IO) {
        try {
            when (preferredMethod ?: detectLoginPortal(context, client)) {
                WifiLoginMethod.WLGN -> loginWithWlgn(context, client, username, password)
                WifiLoginMethod.EPORTAL ->
                    loginWithEportal(context, client, username, password, wlanUserIp)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoginResult.NetworkError(
                context.getString(
                    R.string.campus_direct_request_failed,
                    error.message ?: context.getString(R.string.error_unknown)
                )
            )
        }
    }

private suspend fun detectLoginPortal(context: Context, client: OkHttpClient): WifiLoginMethod {
    val request = Request.Builder()
        .url("http://10.21.221.98/")
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept", "*/*")
        .build()

    client.executeCancellable(request).use { response ->
        when {
            response.code in 300..399 -> {
                val location = response.header("Location").orEmpty()
                return if (location.contains("eportal", ignoreCase = true)) {
                    WifiLoginMethod.EPORTAL
                } else {
                    WifiLoginMethod.WLGN
                }
            }

            response.isSuccessful -> {
                val responseHint = response.peekBody(PORTAL_HINT_BODY_BYTES).string()
                return when {
                    responseHint.contains("wlgn", ignoreCase = true) -> WifiLoginMethod.WLGN
                    responseHint.contains("eportal", ignoreCase = true) -> WifiLoginMethod.EPORTAL
                    // 校园网当前的成功探测页未携带明确标记时，保持既有 Eportal 兼容路径。
                    else -> WifiLoginMethod.EPORTAL
                }
            }

            else -> throw IOException(
                context.getString(R.string.login_portal_detection_failed, response.code)
            )
        }
    }
}

private suspend fun loginWithWlgn(
    context: Context,
    client: OkHttpClient,
    username: String,
    password: String
): LoginResult {
    val params = listOf(
        "callback" to "dr1003",
        "DDDDD" to username,
        "upass" to password,
        "0MKKey" to "123456",
        "R1" to "0",
        "R2" to "",
        "R3" to "0",
        "R6" to "0",
        "para" to "00",
        "v6ip" to "",
        "terminal_type" to "1",
        "lang" to "zh-cn",
        "jsVersion" to "4.1",
        "v" to "3050"
    )

    val request = Request.Builder()
        .url(buildLoginUrl("https", WLGN_HOST, null, "drcom/login", params))
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Referer", "https://$WLGN_HOST/a79.htm")
        .header("Accept", "*/*")
        .build()

    return executeLoginRequest(context, client, request)
}

private suspend fun loginWithEportal(
    context: Context,
    client: OkHttpClient,
    username: String,
    password: String,
    wlanUserIp: String
): LoginResult {
    if (!wlanUserIp.isUsableIpv4()) {
        return LoginResult.Failure(context.getString(R.string.login_no_valid_campus_ip))
    }

    val account = if (username.contains("@")) username else "$username@campus"
    val params = listOf(
        "callback" to "dr1003",
        "login_method" to "1",
        "user_account" to account,
        "user_password" to password,
        "wlan_user_ip" to wlanUserIp,
        "wlan_user_ipv6" to "",
        "wlan_user_mac" to "000000000000",
        "wlan_ac_ip" to "",
        "wlan_ac_name" to "",
        "jsVersion" to "4.2.1",
        "terminal_type" to "1",
        "lang" to "zh",
        "v" to "9842"
    )

    val request = Request.Builder()
        .url(buildLoginUrl("http", EPORTAL_HOST, EPORTAL_PORT, "eportal/portal/login", params))
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept", "*/*")
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
        .header("Cache-Control", "no-cache")
        .header("Pragma", "no-cache")
        .header("Referer", "http://$EPORTAL_HOST/")
        .build()

    return executeLoginRequest(context, client, request)
}

private fun buildLoginUrl(
    scheme: String,
    host: String,
    port: Int?,
    path: String,
    params: List<Pair<String, String>>
): HttpUrl = HttpUrl.Builder()
    .scheme(scheme)
    .host(host)
    .apply { port?.let { selectedPort -> this.port(selectedPort) } }
    .addPathSegments(path)
    .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
    .build()

private suspend fun executeLoginRequest(
    context: Context,
    client: OkHttpClient,
    request: Request
): LoginResult {
    client.executeCancellable(request).use { response ->
        if (!response.isSuccessful) {
            return LoginResult.Failure(
                context.getString(R.string.login_service_http_error, response.code)
            )
        }
        return when (val parsed = LoginResponseParser.parse(response.body?.string().orEmpty())) {
            LoginParseResult.Success -> LoginResult.Success
            LoginParseResult.AlreadyOnline -> LoginResult.AlreadyOnline
            is LoginParseResult.Failure -> LoginResult.Failure(
                loginFailureMessage(context, parsed.serverMessage)
            )
            LoginParseResult.Unknown -> LoginResult.Failure(
                context.getString(R.string.login_unknown_response)
            )
        }
    }
}

private fun loginFailureMessage(context: Context, serverMessage: String?): String = when {
    serverMessage.isWlgnInvalidPasswordMessage() ->
        context.getString(R.string.login_password_error)

    serverMessage.equals("ldap auth error", ignoreCase = true) ->
        context.getString(R.string.login_ldap_error)

    serverMessage.isNullOrBlank() -> context.getString(R.string.login_failed_default)
    else -> context.getString(R.string.login_failed_with_reason, serverMessage)
}

private val wlgnInvalidPasswordPattern = Regex(
    pattern = """^rad\s*:\s*ldap\s+auth\s+error$""",
    option = RegexOption.IGNORE_CASE
)

internal fun String?.isWlgnInvalidPasswordMessage(): Boolean =
    this?.trim()?.matches(wlgnInvalidPasswordPattern) == true

internal suspend fun checkLoginStatus(
    context: Context,
    client: OkHttpClient,
    knownLoggedIn: Boolean = false,
    wlanUserIp: String = "",
    preferredMethod: WifiLoginMethod? = null
): LoginStatus = withContext(Dispatchers.IO) {
    try {
        fetchLoginStatus(client, knownLoggedIn, wlanUserIp, preferredMethod) ?: LoginStatus(
            isLoggedIn = false,
            error = context.getString(R.string.status_verification_failed)
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        LoginStatus(
            isLoggedIn = false,
            error = context.getString(
                R.string.campus_direct_request_failed,
                error.message ?: context.getString(R.string.error_unknown)
            )
        )
    }
}

// 无法通过门户状态接口或页面判断时返回 null，交由调用方显示核验错误。
internal suspend fun fetchLoginStatus(
    client: OkHttpClient,
    knownLoggedIn: Boolean = false,
    wlanUserIp: String = "",
    preferredMethod: WifiLoginMethod? = null
): LoginStatus? {
    var verifiedMethod: WifiLoginMethod? = null
    if (!knownLoggedIn) {
        val verification = verifyLoginStatus(client, wlanUserIp, preferredMethod)
        verifiedMethod = verification.method
        when (verification.state) {
            LoginRecheckResult.LoggedOut -> return LoginStatus(
                isLoggedIn = false,
                verifiedMethod = verifiedMethod
            )
            LoginRecheckResult.Unknown -> return null
            LoginRecheckResult.LoggedIn -> Unit
        }
    }

    // 登录页或认证接口已确认在线；lgn 仅用于补充当前认证账号，失败不得改变登录状态。
    val status = try {
        retryAfterDnsFailure {
            fetchAuthenticatedLoginStatus(client)
        } ?: LoginStatus(isLoggedIn = true)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        LoginStatus(isLoggedIn = true)
    }
    return status.copy(verifiedMethod = verifiedMethod)
}

internal suspend fun recheckLoginStatus(
    client: OkHttpClient,
    wlanUserIp: String = ""
): LoginRecheckResult = verifyLoginStatus(client, wlanUserIp).state

private data class LoginVerification(
    val state: LoginRecheckResult,
    val method: WifiLoginMethod? = null
)

private suspend fun verifyLoginStatus(
    client: OkHttpClient,
    wlanUserIp: String,
    preferredMethod: WifiLoginMethod? = null
): LoginVerification {
    val verificationClient = client.newBuilder()
        .followRedirects(false)
        .connectTimeout(LOGIN_RECHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(LOGIN_RECHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(LOGIN_RECHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    val methods = (listOfNotNull(preferredMethod) +
        listOf(WifiLoginMethod.EPORTAL, WifiLoginMethod.WLGN)).distinct()
    var loginPageMethod: WifiLoginMethod? = null
    var unresolvedDynamicPortal = false
    for (method in methods) {
        val url = when (method) {
            WifiLoginMethod.EPORTAL -> "http://$EPORTAL_HOST/"
            WifiLoginMethod.WLGN -> "https://$WLGN_HOST/a79.htm"
        }
        val check = checkLoginPortalState(verificationClient, url, wlanUserIp)
        unresolvedDynamicPortal = unresolvedDynamicPortal || check.unresolvedDynamicPortal
        when (check.state) {
            LoginRecheckResult.LoggedIn -> return LoginVerification(check.state, method)
            LoginRecheckResult.LoggedOut -> {
                // 已知门户明确确认离线时直接返回，首次探测仍检查另一门户以避免误判。
                if (method == preferredMethod) return LoginVerification(check.state, method)
                if (loginPageMethod == null) loginPageMethod = method
            }
            LoginRecheckResult.Unknown -> Unit
        }
    }
    return if (loginPageMethod != null && !unresolvedDynamicPortal) {
        LoginVerification(LoginRecheckResult.LoggedOut, loginPageMethod)
    } else {
        LoginVerification(LoginRecheckResult.Unknown)
    }
}

private data class PortalStateCheck(
    val state: LoginRecheckResult,
    val unresolvedDynamicPortal: Boolean
)

private suspend fun checkLoginPortalState(
    client: OkHttpClient,
    url: String,
    wlanUserIp: String
): PortalStateCheck {
    val request = Request.Builder()
        .url(url)
        .get()
        .header("User-Agent", USER_AGENT)
        .header("Accept", "*/*")
        .build()

    var dynamicPortal = false
    val state = try {
        client.executeCancellable(request).use { response ->
            val location = response.header("Location").orEmpty()
            if (response.code in 300..399 && location.pointsToLgn()) {
                return@use LoginRecheckResult.LoggedIn
            }
            if (response.code !in 200..299) {
                return@use LoginRecheckResult.Unknown
            }

            val body = response.body?.bytes() ?: byteArrayOf()
            val titleStatus = body.loginStatusFromPageTitle()
            if (titleStatus == LoginRecheckResult.LoggedIn) {
                LoginRecheckResult.LoggedIn
            } else if (body.isDynamicCampusPortal()) {
                dynamicPortal = true
                fetchDynamicPortalStatus(client, request, body, wlanUserIp)
            } else {
                titleStatus
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        LoginRecheckResult.Unknown
    }
    return PortalStateCheck(state, dynamicPortal && state == LoginRecheckResult.Unknown)
}

private suspend fun fetchAuthenticatedLoginStatus(
    client: OkHttpClient
): LoginStatus? {
    val request = Request.Builder()
        .url("https://lgn.bjut.edu.cn/")
        .get()
        .header("User-Agent", USER_AGENT)
        .build()

    client.executeCancellable(request).use { response ->
        if (!response.isSuccessful) return null

        // 服务器返回 GB2312；日志拦截器只查看副本，不会改变这里的原始字节。
        val bodyBytes = response.body?.bytes() ?: byteArrayOf()
        val body = bodyBytes.toString(Charset.forName("GB2312"))
        val isLoggedIn = bodyBytes.isCampusLogoutPage()

        return if (isLoggedIn) {
            LoginStatus(
                isLoggedIn = true,
                uid = body.extractPageVariable("uid"),
                flow = body.extractPageVariable("flow"),
                time = body.extractPageVariable("time"),
                v4ip = body.extractPageVariable("v4ip")
            )
        } else {
            null
        }
    }
}

internal fun ByteArray.isCampusLoginPage(): Boolean =
    loginStatusFromPageTitle() == LoginRecheckResult.LoggedOut

internal fun ByteArray.loginStatusFromPageTitle(): LoginRecheckResult {
    for (charset in LOGIN_PAGE_CHARSETS) {
        val body = toString(charset)
        when {
            LOGIN_PAGE_TITLE_REGEX.containsMatchIn(body) -> return LoginRecheckResult.LoggedOut
            LOGOUT_PAGE_TITLE_REGEX.containsMatchIn(body) -> return LoginRecheckResult.LoggedIn
        }
    }
    return LoginRecheckResult.Unknown
}

internal fun ByteArray.isCampusLogoutPage(): Boolean =
    loginStatusFromPageTitle() == LoginRecheckResult.LoggedIn ||
            LOGIN_PAGE_CHARSETS.any { charset ->
                toString(charset).contains("Dr.COMWebLoginID_1.htm", ignoreCase = true)
            }

private fun String.pointsToLgn(): Boolean =
    toHttpUrlOrNull()?.host == "lgn.bjut.edu.cn"

private fun String.extractPageVariable(name: String): String =
    Regex("$name\\s*=\\s*'([^']*)'").find(this)?.groupValues?.get(1)?.trim().orEmpty()

internal enum class LoginRecheckResult {
    LoggedIn,
    LoggedOut,
    Unknown
}

private const val WLGN_HOST = "wlgn.bjut.edu.cn"
private const val EPORTAL_HOST = "10.21.221.98"
private const val EPORTAL_PORT = 801
private const val LOGIN_RECHECK_TIMEOUT_SECONDS = 3L
private const val PORTAL_HINT_BODY_BYTES = 8_192L
private val LOGIN_PAGE_TITLE_REGEX = Regex(
    pattern = """<title\b[^>]*>\s*上网登录页\s*</title\s*>""",
    option = RegexOption.IGNORE_CASE
)
private val LOGOUT_PAGE_TITLE_REGEX = Regex(
    pattern = """<title\b[^>]*>\s*注销页\s*</title\s*>""",
    option = RegexOption.IGNORE_CASE
)
private val LOGIN_PAGE_CHARSETS = listOf(Charsets.UTF_8, Charset.forName("GB2312"))
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0"
