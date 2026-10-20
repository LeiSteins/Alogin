package top.steins.autologin.network

import org.junit.Assert.*
import org.junit.Test
import top.steins.autologin.domain.model.BoundDeviceList
import top.steins.autologin.domain.model.SeamlessStatus

class SeamlessParsingTest {
    @Test
    fun status_decodesJsonHtmlAndKeepsUnrecognizedResponsesUnknown() {
        assertEquals(SeamlessStatus.Enabled, SeamlessParsing.parseStatus("\"<a data-toggle='oprateMauth'>开启</a>\""))
        assertEquals(SeamlessStatus.Disabled, SeamlessParsing.parseStatus("\"<span>关闭</span>\""))
        for (body in listOf("", "null", "{}", "<html>登录</html>", "\"未知\"")) {
            assertEquals(SeamlessStatus.Unknown, SeamlessParsing.parseStatus(body))
        }
    }

    @Test
    fun deviceList_mapsStringFieldsAndPreservesOfflineBindings() {
        val result = SeamlessParsing.parseDevices("""{"total":2,"rows":[["1","aabbcc112233","#Android","2026-10-20 09:00:00","10.1.2.3"],["0","001122334455","","",""]]}""")!!
        assertEquals(2, result.total)
        assertEquals("AA:BB:CC:11:22:33", result.devices[0].macAddress)
        assertEquals("Android", result.devices[0].terminalInfo)
        assertEquals(true, result.devices[0].isOnline)
        assertEquals(false, result.devices[1].isOnline)
        assertEquals("", result.devices[1].lastLoginTime)
    }

    @Test
    fun deviceList_distinguishesEmptyListFromMissingOrInvalidData() {
        assertEquals(BoundDeviceList(0, emptyList()), SeamlessParsing.parseDevices("""{"total":0,"rows":[]}"""))
        for (body in listOf("", "null", "[]", "{}", """{"total":-1,"rows":[]}""",
            """{"total":1,"rows":[["1","invalid","","",""]]}""",
            """{"total":1,"rows":[["1","001122334455"]]}""",
            """{"total":0,"rows":[["1","001122334455","","",""]]}""")) {
            assertNull(SeamlessParsing.parseDevices(body))
        }
    }

    @Test
    fun macInput_normalizesSupportedSeparatorsWithoutSilentlyDiscardingOtherCharacters() {
        for (mac in listOf("aabbcc112233", "aa:bb:cc:11:22:33", "aa-bb-cc-11-22-33")) {
            assertEquals("AABBCC112233", SeamlessParsing.canonicalMac(mac))
        }
        for (mac in listOf("aa!bb!cc!11!22!33", "aa:bb-cc:11:22:33", "aabbcc11223g", "aabbcc")) {
            assertNull(SeamlessParsing.canonicalMac(mac))
        }
    }

    @Test
    fun unbindToken_readsCurrentPageScriptRatherThanUnrelatedRefreshToken() {
        val page = """csrftoken: 'refresh-token'; window.location.href = '/Self/service/unbindmac?mac=' + mac + "&ajaxCsrfToken=" + 'action-token';"""
        assertEquals("action-token", SeamlessParsing.parseUnbindToken(page))
        assertNull(SeamlessParsing.parseUnbindToken("<form action='/Self/login/verify'>"))
    }
}
