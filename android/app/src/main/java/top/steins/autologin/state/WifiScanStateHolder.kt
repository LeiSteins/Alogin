package top.steins.autologin.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.steins.autologin.AppStrings
import top.steins.autologin.R
import top.steins.autologin.domain.model.WifiScanOutcome

data class WifiScanUiState(
    val isScanning: Boolean = false,
    val outcome: WifiScanOutcome? = null
)

/** 扫描可随面板关闭而取消；代次检查阻止旧任务覆盖重新打开的面板。 */
class WifiScanStateHolder(
    private val scope: CoroutineScope,
    private val scan: suspend () -> WifiScanOutcome,
    private val strings: AppStrings
) {
    private val _state = MutableStateFlow(WifiScanUiState())
    val state = _state.asStateFlow()
    private var scanJob: Job? = null
    private var generation = 0L

    fun refresh() {
        if (_state.value.isScanning) return
        val currentGeneration = ++generation
        _state.value = _state.value.copy(isScanning = true)
        scanJob = scope.launch {
            try {
                val outcome = scan()
                if (currentGeneration == generation) {
                    _state.value = WifiScanUiState(outcome = outcome)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (currentGeneration == generation) {
                    _state.value = WifiScanUiState(
                        outcome = WifiScanOutcome.Failure(strings.get(R.string.wifi_scan_failed))
                    )
                }
            } finally {
                if (currentGeneration == generation) {
                    _state.value = _state.value.copy(isScanning = false)
                }
            }
        }
    }

    fun permissionDenied() {
        cancel()
        _state.value = WifiScanUiState(outcome = WifiScanOutcome.PermissionDenied)
    }

    fun cancel() {
        generation += 1
        scanJob?.cancel()
        scanJob = null
        _state.value = WifiScanUiState()
    }
}
