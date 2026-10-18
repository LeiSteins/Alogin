package top.steins.autologin.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginResponseParserTest {

    @Test
    fun parse_recognizesJsonpSuccess() {
        val result = LoginResponseParser.parse("dr1003({\"result\":1,\"msg\":\"login_ok\"})")

        assertEquals(LoginParseResult.Success, result)
    }

    @Test
    fun parse_recognizesEportalIpAlreadyOnlineResponse() {
        val response = """dr1003({"result":0,"msg":"IP: 10.1.2.3 已经在线! ","ret_code":2});"""

        assertEquals(LoginParseResult.AlreadyOnline, LoginResponseParser.parse(response))
    }

    @Test
    fun parse_recognizesIpAlreadyOnlineInMsga() {
        val response = """dr1003({"result":0,"msg":1,"msga":"IP：10.1.2.3已在线！"});"""

        assertEquals(LoginParseResult.AlreadyOnline, LoginResponseParser.parse(response))
    }

    @Test
    fun parse_recognizesPlainIpAlreadyOnlineMessage() {
        assertEquals(LoginParseResult.AlreadyOnline, LoginResponseParser.parse("IP 已经在线"))
    }

    @Test
    fun parse_doesNotTreatAccountAlreadyOnlineAsCurrentIpOnline() {
        val response = """dr1003({"result":0,"msg":"账号已经在线，无法登录"});"""

        assertEquals(
            LoginParseResult.Failure("账号已经在线，无法登录"),
            LoginResponseParser.parse(response)
        )
    }

    @Test
    fun parse_doesNotTreatRetCodeAloneAsCurrentIpOnline() {
        val response = """dr1003({"result":0,"msg":"密码错误","ret_code":2});"""

        assertEquals(LoginParseResult.Failure("密码错误"), LoginResponseParser.parse(response))
    }

    @Test
    fun parse_keepsServerFailureMessage() {
        val result = LoginResponseParser.parse("dr1003({\"result\":0,\"msg\":\"密码错误\"})")

        assertTrue(result is LoginParseResult.Failure)
        assertEquals("密码错误", (result as LoginParseResult.Failure).serverMessage)
    }

    @Test
    fun parse_keepsLdapAuthenticationServerMessage() {
        val result = LoginResponseParser.parse("dr1003({\"result\":0,\"msg\":\"ldap auth error\"})")

        assertTrue(result is LoginParseResult.Failure)
        assertEquals("ldap auth error", (result as LoginParseResult.Failure).serverMessage)
    }

    @Test
    fun parse_readsWlgnFailureReasonFromMsgaWhenMsgIsNumeric() {
        val response = """
            dr1003({
                "result": 0,
                "msg": 1,
                "uid": "25028021",
                "msga": "Rad:ldap auth error"
            })
        """.trimIndent()

        val result = LoginResponseParser.parse(response)

        assertTrue(result is LoginParseResult.Failure)
        assertEquals("Rad:ldap auth error", (result as LoginParseResult.Failure).serverMessage)
    }

    @Test
    fun isWlgnInvalidPasswordMessage_recognizesRadLdapError() {
        assertTrue("Rad:ldap auth error".isWlgnInvalidPasswordMessage())
        assertTrue(!"ldap auth error".isWlgnInvalidPasswordMessage())
    }

    @Test
    fun isUsableIpv4_rejectsOutOfRangeSegments() {
        assertTrue("10.21.221.98".isUsableIpv4())
        assertTrue(!"10.21.221.999".isUsableIpv4())
        assertTrue(!"10.21.221".isUsableIpv4())
    }
}
