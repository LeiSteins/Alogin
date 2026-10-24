package top.steins.autologin.domain.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacAddressTest {
    @Test
    fun macInput_normalizesSupportedSeparatorsWithoutSilentlyDiscardingOtherCharacters() {
        for (mac in listOf("aabbcc112233", "aa:bb:cc:11:22:33", "aa-bb-cc-11-22-33")) {
            assertEquals("AABBCC112233", canonicalMac(mac))
        }
        for (mac in listOf("aa!bb!cc!11!22!33", "aa:bb-cc:11:22:33", "aabbcc11223g", "aabbcc")) {
            assertNull(canonicalMac(mac))
        }
    }
}
