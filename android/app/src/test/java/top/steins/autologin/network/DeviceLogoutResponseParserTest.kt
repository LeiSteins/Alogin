package top.steins.autologin.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DeviceLogoutResponseParserTest {

    @Test
    fun parse_dashboardSuccess_returnsSuccess() {
        assertSame(
            DeviceLogoutResponse.Success,
            DeviceLogoutResponseParser.parse("""{"success":true}""")
        )
    }

    @Test
    fun parse_dashboardFailure_returnsServerMessage() {
        assertEquals(
            DeviceLogoutResponse.Failure("在线会话不存在"),
            DeviceLogoutResponseParser.parse(
                """{"success":false,"msg":"在线会话不存在"}"""
            )
        )
    }

    @Test
    fun parse_numericSuccess_returnsSuccess() {
        assertSame(
            DeviceLogoutResponse.Success,
            DeviceLogoutResponseParser.parse("""{"success":1}""")
        )
    }

    @Test
    fun parse_stringFailure_returnsFailure() {
        assertEquals(
            DeviceLogoutResponse.Failure(null),
            DeviceLogoutResponseParser.parse("""{"success":"false"}""")
        )
    }

    @Test
    fun parse_unknownResponse_returnsUnknown() {
        assertSame(
            DeviceLogoutResponse.Unknown,
            DeviceLogoutResponseParser.parse("<html><body>我的设备</body></html>")
        )
    }
}
