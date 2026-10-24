package top.steins.autologin.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

fun hasWifiLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

/** WifiManager.startScan() 与 scanResults 在 Android 13+ 上仍要求精确定位权限。 */
fun hasWifiScanPermission(context: Context): Boolean = hasWifiLocationPermission(context)

/**
 * WifiManager.startScan() 在所有受支持的系统版本上都需要精确定位权限。
 */
fun wifiScanPermissionsForRequest(): Array<String> =
    arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
