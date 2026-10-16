package top.steins.autologin

import top.steins.autologin.data.TargetWifiConfigChange
import top.steins.autologin.data.TargetWifiConfigChangeType
import top.steins.autologin.network.DefaultNetworkChange

internal enum class RefreshAttemptOutcome {
    Complete,
    LoginSucceeded,
    RetryableFailure
}

internal sealed interface AccountInfoRefreshTrigger {
    fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String

    data class DefaultNetworkChanged(val change: DefaultNetworkChange) : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String {
            val messageRes = when (change) {
                DefaultNetworkChange.INITIAL -> R.string.refresh_trigger_initial
                DefaultNetworkChange.IP_ADDRESS_CHANGED -> R.string.refresh_trigger_ip_changed
                DefaultNetworkChange.SSID_CHANGED -> R.string.refresh_trigger_ssid_changed
                DefaultNetworkChange.IP_ADDRESS_AND_SSID_CHANGED ->
                    R.string.refresh_trigger_ip_ssid_changed
            }
            return strings.get(messageRes)
        }
    }

    data class LocationPermissionResult(val granted: Boolean) : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String {
            val permissionText = strings.get(
                if (granted) R.string.permission_granted else R.string.permission_denied
            )
            return strings.get(R.string.refresh_trigger_permission_result, permissionText)
        }
    }

    data object AppForegrounded : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_foreground, attempt, totalAttempts)
    }

    data class TargetWifiConfigurationChanged(
        val change: TargetWifiConfigChange
    ) : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String {
            val messageRes = when (change.type) {
                TargetWifiConfigChangeType.ADDED -> R.string.refresh_trigger_wifi_added
                TargetWifiConfigChangeType.REMOVED -> R.string.refresh_trigger_wifi_removed
            }
            return strings.get(messageRes, change.ssid)
        }
    }

    data object ManualNetworkStatusCheck : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_manual_network_check)
    }

    data object ManualAccountInfoRefresh : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_manual_account_refresh)
    }

    data object AccountInfoRetry : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_account_retry)
    }

    data class DeviceLogoutSucceeded(
        val successfulDeviceCount: Int
    ) : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(
                R.string.refresh_trigger_device_logout_succeeded,
                successfulDeviceCount
            )
    }

    data object DeviceLogoutIndeterminate : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_device_logout_indeterminate)
    }

    data object LoginConfirmation : AccountInfoRefreshTrigger {
        override fun description(strings: AppStrings, attempt: Int, totalAttempts: Int): String =
            strings.get(R.string.refresh_trigger_login_confirmation, attempt, totalAttempts)
    }
}
