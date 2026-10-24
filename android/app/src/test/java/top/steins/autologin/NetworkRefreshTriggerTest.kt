package top.steins.autologin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.steins.autologin.domain.model.CampusNetworkChange
import top.steins.autologin.network.NetworkRefreshSnapshot
import top.steins.autologin.network.detectCampusNetworkChange

class NetworkRefreshTriggerTest {

    private val previous = NetworkRefreshSnapshot(
        ipAddress = "10.21.1.10",
        ssid = "bjut_wifi"
    )

    @Test
    fun detectCampusNetworkChange_returnsInitialForFirstSnapshot() {
        assertEquals(
            CampusNetworkChange.INITIAL,
            detectCampusNetworkChange(previous = null, current = previous)
        )
    }

    @Test
    fun detectCampusNetworkChange_ignoresUnchangedSnapshot() {
        assertNull(detectCampusNetworkChange(previous, previous))
    }

    @Test
    fun detectCampusNetworkChange_ignoresSsidWhenItWasNotReadable() {
        assertNull(
            detectCampusNetworkChange(
                previous.copy(ssid = null, isSsidReadable = false),
                previous
            )
        )
    }

    @Test
    fun detectCampusNetworkChange_reportsOnlyIpOrSsidChanges() {
        assertEquals(
            CampusNetworkChange.IP_ADDRESS_CHANGED,
            detectCampusNetworkChange(
                previous,
                previous.copy(ipAddress = "10.21.1.11")
            )
        )
        assertEquals(
            CampusNetworkChange.SSID_CHANGED,
            detectCampusNetworkChange(
                previous,
                previous.copy(ssid = "bjut_wifi_2")
            )
        )
        assertEquals(
            CampusNetworkChange.IP_ADDRESS_AND_SSID_CHANGED,
            detectCampusNetworkChange(
                previous,
                NetworkRefreshSnapshot(ipAddress = "10.21.1.11", ssid = "bjut_wifi_2")
            )
        )
    }
}
