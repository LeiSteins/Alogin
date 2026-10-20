package top.steins.autologin.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import top.steins.autologin.domain.model.WifiLoginMethod

class SettingsWifiLoginMethodTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("wifi_method_test_$name", mode)
    }
    private val prefs get() = context.getSharedPreferences("alogin_settings", Context.MODE_PRIVATE)

    @Before
    @After
    fun clearTestPreferences() {
        prefs.edit().clear().commit()
    }

    @Test
    fun verifiedMethods_surviveRestartAndRemainSeparateForEachSsid() {
        val repository = SettingsRepository(context)
        repository.addTargetWifi("宿舍,Wi-Fi:1")
        repository.saveWifiLoginMethod("bjut_wifi", WifiLoginMethod.WLGN)
        repository.saveWifiLoginMethod("宿舍,Wi-Fi:1", WifiLoginMethod.EPORTAL)

        val restored = SettingsRepository(context)
        assertEquals(WifiLoginMethod.WLGN, restored.getWifiLoginMethod("bjut_wifi"))
        assertEquals(WifiLoginMethod.EPORTAL, restored.getWifiLoginMethod("宿舍,Wi-Fi:1"))

        restored.saveWifiLoginMethod("bjut_wifi", WifiLoginMethod.EPORTAL)
        assertEquals(WifiLoginMethod.EPORTAL, SettingsRepository(context).getWifiLoginMethod("bjut_wifi"))
    }

    @Test
    fun removingTargetWifi_clearsMethodAfterRestartAndReadding() {
        val repository = SettingsRepository(context)
        repository.saveWifiLoginMethod("bjut_wifi", WifiLoginMethod.WLGN)
        repository.removeTargetWifi("bjut_wifi")

        val restored = SettingsRepository(context)
        restored.addTargetWifi("bjut_wifi")
        assertNull(restored.getWifiLoginMethod("bjut_wifi"))
    }

    @Test
    fun nonTargetWifi_doesNotAcquireMethodWhenAddedLater() {
        val repository = SettingsRepository(context)
        repository.saveWifiLoginMethod("guest", WifiLoginMethod.WLGN)
        repository.addTargetWifi("guest")

        assertNull(SettingsRepository(context).getWifiLoginMethod("guest"))
    }

    @Test
    fun existingAndUnknownStoredMethods_requireFreshVerification() {
        assertNull(SettingsRepository(context).getWifiLoginMethod("bjut_wifi"))
        prefs.edit().putString("wifi_login_method_v1:bjut_wifi", "unsupported").commit()

        assertNull(SettingsRepository(context).getWifiLoginMethod("bjut_wifi"))
    }

    @Test
    fun autoDetectedTargetWifi_canRememberMethod() {
        val repository = SettingsRepository(context)
        repository.addAutoDetectedTargetWifi("bjut-sushe-1")
        repository.saveWifiLoginMethod("bjut-sushe-1", WifiLoginMethod.EPORTAL)

        assertEquals(WifiLoginMethod.EPORTAL, SettingsRepository(context).getWifiLoginMethod("bjut-sushe-1"))
    }
}
