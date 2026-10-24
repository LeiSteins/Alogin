package top.steins.autologin.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkInfoUtilsTest {

    @Test
    fun selectWifiSsid_usesTransportInfoWhenItsSsidIsAvailable() {
        assertEquals("campus_wifi", selectWifiSsid("\"campus_wifi\"", "fallback_wifi"))
    }

    @Test
    fun selectWifiSsid_fallsBackWhenTransportInfoIsRedacted() {
        assertEquals("campus_wifi", selectWifiSsid("<unknown ssid>", "\"campus_wifi\""))
    }

    @Test
    fun selectWifiSsid_keepsUnknownWhenNeitherSourceIsReadable() {
        assertEquals("<unknown ssid>", selectWifiSsid("<unknown ssid>", "<unknown ssid>"))
    }
}
