package top.steins.autologin

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import top.steins.autologin.data.SettingsRepository
import top.steins.autologin.network.DefaultCampusNetworkProvider
import top.steins.autologin.network.DefaultNetworkEnvironment
import top.steins.autologin.network.SelfServiceRepository
import top.steins.autologin.network.hasWifiLocationPermission
import top.steins.autologin.network.scanNearbyWifi
import top.steins.autologin.network.update.UpdateRepository

/** 集中组装生产依赖，让 AppViewModel 本体保持可脱离 Android 框架测试。 */
fun appViewModelFactory(application: Application): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(AppViewModel::class.java)) {
                "Unexpected ViewModel class: ${modelClass.name}"
            }
            val campusNetwork = DefaultCampusNetworkProvider(application)
            val settings = SettingsRepository(application)
            return AppViewModel(
                strings = AppStrings { resId, args ->
                    application.getString(resId, *args)
                },
                accountStore = settings,
                wifiConfig = settings,
                appPreferences = settings,
                logPreferences = settings,
                updateCheckStore = settings,
                selfService = SelfServiceRepository(application, campusNetwork),
                updates = UpdateRepository(application),
                network = DefaultNetworkEnvironment(application, campusNetwork),
                hasLocationPermission = { hasWifiLocationPermission(application) },
                scanWifi = { scanNearbyWifi(application) }
            ) as T
        }
    }
