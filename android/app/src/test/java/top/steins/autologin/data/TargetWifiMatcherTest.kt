package top.steins.autologin.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetWifiMatcherTest {

    @Test
    fun isBjutDormitoryWifi_matchesDormitoryWifiPrefix() {
        assertTrue(isBjutDormitoryWifi("bjut-sushe-test"))
    }

    @Test
    fun isBjutDormitoryWifi_rejectsOtherWifiNames() {
        assertFalse(isBjutDormitoryWifi("bjut_wifi"))
        assertFalse(isBjutDormitoryWifi("bjut-sushe"))
        assertFalse(isBjutDormitoryWifi("BJUT-sushe-5G-Y6b2"))
    }
}
