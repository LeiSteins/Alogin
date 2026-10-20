package top.steins.autologin.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.steins.autologin.domain.model.AccountDevice

class SelfServiceParsingTest {

    @Test
    fun findJsonObject_returnsBalancedObject() {
        val extraction = SelfServiceParsing.findJsonObject("callback({\"a\":1});")

        assertEquals(
            JsonObjectExtraction.Found("""{"a":1}"""),
            extraction
        )
    }

    @Test
    fun findJsonObject_skipsUntilMarkerAndHandlesNestedBraces() {
        val text = """prefix {"ignore":1} })( {"real":{"nested":true}} tail"""

        val extraction = SelfServiceParsing.findJsonObject(text, marker = "})(")

        assertEquals(
            JsonObjectExtraction.Found("""{"real":{"nested":true}}"""),
            extraction
        )
    }

    @Test
    fun findJsonObject_extractsAccountDataEmbeddedInDashboard() {
        val dashboardHtml = """
            <script>
                (function (user) {
                    window.user = user || {};
                })({"userName":"student","internetDownFlow":1024.5,"leftFlow":2048.5,"leftMoney":30.5});
            </script>
        """.trimIndent()

        val extraction = SelfServiceParsing.findJsonObject(dashboardHtml, marker = "})(")

        assertEquals(
            JsonObjectExtraction.Found(
                """{"userName":"student","internetDownFlow":1024.5,"leftFlow":2048.5,"leftMoney":30.5}"""
            ),
            extraction
        )
    }

    @Test
    fun findJsonObject_handlesEscapedQuotes() {
        val text = """{"a":"say \"hi\"","b":{"c":1}}"""

        assertEquals(
            JsonObjectExtraction.Found(text),
            SelfServiceParsing.findJsonObject(text)
        )
    }

    @Test
    fun findJsonObject_reportsMissingAndIncomplete() {
        assertEquals(
            JsonObjectExtraction.Missing,
            SelfServiceParsing.findJsonObject("no json here")
        )
        assertEquals(
            JsonObjectExtraction.Incomplete,
            SelfServiceParsing.findJsonObject("""{"a":1""")
        )
    }

    @Test
    fun parseOnlineDevices_mapsDashboardSessionFields() {
        val response = """
            [{
                "sessionId":"session-1",
                "ip":"10.1.2.3",
                "ipv6":"2001:db8::1",
                "mac":"aa-bb-cc-11-22-33",
                "loginTime":"2026-09-21 10:00:00",
                "useTime":"120",
                "downFlow":"2048",
                "upFlow":"1024",
                "hostName":"phone",
                "terminalType":"Android"
            }]
        """.trimIndent()

        val devices = SelfServiceParsing.parseOnlineDevices(response)

        assertEquals(1, devices?.size)
        val device = devices?.single()
        assertEquals("session-1", device?.sessionId)
        assertEquals("AA:BB:CC:11:22:33", device?.macAddress)
        assertEquals("10.1.2.3", device?.ipAddress)
        assertEquals("2001:db8::1", device?.ipv6Address)
        assertEquals("2026-09-21 10:00:00", device?.loginTime)
        assertEquals("120", device?.useTimeSeconds)
        assertEquals("2048", device?.downFlow)
        assertEquals("1024", device?.upFlow)
        assertEquals("phone", device?.hostName)
        assertEquals("Android", device?.terminalType)
    }

    @Test
    fun parseOnlineDevices_acceptsEmptyOnlineList() {
        assertEquals(emptyList<AccountDevice>(), SelfServiceParsing.parseOnlineDevices("[]"))
    }

    @Test
    fun parseOnlineDevices_returnsNullForMalformedJson() {
        assertNull(SelfServiceParsing.parseOnlineDevices("not json"))
    }

    @Test
    fun parseOnlineDevices_rejectsRowsMissingOfflineParameters() {
        assertNull(
            SelfServiceParsing.parseOnlineDevices(
                """[{"sessionId":"","ip":"10.1.2.3","mac":"AABBCC112233"}]"""
            )
        )
        assertNull(
            SelfServiceParsing.parseOnlineDevices(
                """[{"sessionId":"session-1","ip":"","mac":"AABBCC112233"}]"""
            )
        )
        assertNull(
            SelfServiceParsing.parseOnlineDevices(
                """[{"sessionId":"session-1","ip":"10.1.2.3","mac":"invalid"}]"""
            )
        )
    }

    @Test
    fun canonicalMac_acceptsOnlyValidHexOfLengthTwelve() {
        assertEquals("AABBCC112233", SelfServiceParsing.canonicalMac("aa:bb:cc:11:22:33"))
        assertEquals("AABBCC112233", SelfServiceParsing.canonicalMac("AA-BB-CC-11-22-33"))
        assertNull(SelfServiceParsing.canonicalMac("aabbcc"))
        assertNull(SelfServiceParsing.canonicalMac("ggbbcc112233"))
    }

    @Test
    fun formatMac_groupsIntoColonSeparatedPairs() {
        assertEquals("AA:BB:CC:11:22:33", SelfServiceParsing.formatMac("AABBCC112233"))
    }

    @Test
    fun xorEncode_encodesEachCharacterWith0x16() {
        assertEquals("726427262622", SelfServiceParsing.xorEncode("dr1004"))
    }
}
