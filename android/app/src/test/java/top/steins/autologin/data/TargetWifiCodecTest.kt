package top.steins.autologin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetWifiCodecTest {

    @Test
    fun decode_roundTripsSsidContainingCommaAndColon() {
        val ssids = listOf("bjut_wifi", "lab,2.4G", "name:with:colon")

        assertEquals(ssids, TargetWifiCodec.decode(TargetWifiCodec.encode(ssids)))
    }

    @Test
    fun decode_preservesAnExplicitEmptyList() {
        assertEquals(emptyList<String>(), TargetWifiCodec.decode(TargetWifiCodec.encode(emptyList())))
    }

    @Test
    fun decode_returnsNullForLegacyCommaSeparatedValue() {
        assertNull(TargetWifiCodec.decode("bjut_wifi,guest"))
    }
}
