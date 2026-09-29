package top.steins.autologin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch
import top.steins.autologin.network.update.UpdateInstaller
import top.steins.autologin.data.AppearanceMode
import top.steins.autologin.ui.theme.AloginTheme

class MainActivity : ComponentActivity() {
    private val updateInstaller by lazy { UpdateInstaller(this) }
    private var permissionVersionCode: Int? = null
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val versionCode = permissionVersionCode
        permissionVersionCode = null
        if (versionCode != null) {
            if (updateInstaller.canInstall()) {
                // ActivityResult 可能在 onResume 前送达，待前台生命周期恢复再继续。
                lifecycleScope.launch {
                    lifecycle.withResumed {
                        appViewModel.resumeUpdateInstallation(versionCode)
                    }
                }
            } else {
                appViewModel.showUpdateMessage(R.string.update_install_permission_denied)
            }
        }
    }
    private val appViewModel: AppViewModel by lazy {
        ViewModelProvider(this, appViewModelFactory(application))[AppViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionVersionCode = savedInstanceState?.getInt("update_permission_version", -1)?.takeIf { it > 0 }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                appViewModel.updateInstallation.collect { update ->
                    if (update == null) return@collect
                    appViewModel.consumeUpdateInstallation()
                    val file = appViewModel.validatedUpdateDownload(update) ?: return@collect
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
                    try {
                        if (updateInstaller.canInstall()) {
                            startActivity(updateInstaller.installIntent(file))
                        } else {
                            permissionVersionCode = update.versionCode
                            installPermission.launch(updateInstaller.permissionIntent())
                        }
                    } catch (_: android.content.ActivityNotFoundException) {
                        permissionVersionCode = null
                        appViewModel.showUpdateMessage(R.string.update_installer_unavailable)
                    } catch (_: SecurityException) {
                        permissionVersionCode = null
                        appViewModel.showUpdateMessage(R.string.update_install_blocked)
                    } catch (_: IllegalArgumentException) {
                        appViewModel.showUpdateMessage(R.string.update_installer_unavailable)
                    }
                }
            }
        }
        enableEdgeToEdge()
        setContent {
            val appearanceMode by appViewModel.appearanceMode
                .collectAsStateWithLifecycle()
            val darkTheme = when (appearanceMode) {
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
                AppearanceMode.LIGHT -> false
                AppearanceMode.DARK -> true
            }
            AloginTheme(darkTheme = darkTheme) {
                AppRoot(viewModel = appViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appViewModel.onAppForegrounded()
        appViewModel.setUpdateForeground(true)
    }

    override fun onPause() {
        appViewModel.setUpdateForeground(false)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        permissionVersionCode?.let { outState.putInt("update_permission_version", it) }
        super.onSaveInstanceState(outState)
    }
}
