package top.steins.autologin.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.steins.autologin.AppStrings
import top.steins.autologin.R
import top.steins.autologin.domain.model.WifiScanOutcome
import top.steins.autologin.domain.model.WifiScanResult

@OptIn(ExperimentalCoroutinesApi::class)
class WifiScanStateHolderTest {
    private val strings = AppStrings { resource, _ -> "msg:$resource" }

    @Test
    fun repeatedRefresh_submitsOneScanAndPublishesResult() = runTest {
        val result = CompletableDeferred<WifiScanOutcome>()
        var requests = 0
        val holder = WifiScanStateHolder(backgroundScope, {
            requests++
            result.await()
        }, strings)

        holder.refresh()
        holder.refresh()
        runCurrent()
        assertEquals(1, requests)
        assertTrue(holder.state.value.isScanning)

        val success = WifiScanOutcome.Success(listOf(WifiScanResult("Campus", -40, true)), true)
        result.complete(success)
        runCurrent()
        assertEquals(success, holder.state.value.outcome)
        assertFalse(holder.state.value.isScanning)
    }

    @Test
    fun cancelAndReopen_oldResultCannotReplaceNewScan() = runTest {
        val oldResult = CompletableDeferred<WifiScanOutcome>()
        val newResult = CompletableDeferred<WifiScanOutcome>()
        var requests = 0
        val holder = WifiScanStateHolder(backgroundScope, {
            requests++
            if (requests == 1) withContext(NonCancellable) { oldResult.await() }
            else newResult.await()
        }, strings)

        holder.refresh()
        runCurrent()
        holder.cancel()
        assertNull(holder.state.value.outcome)
        assertFalse(holder.state.value.isScanning)
        holder.refresh()
        runCurrent()
        assertEquals(2, requests)

        oldResult.complete(WifiScanOutcome.NoResults)
        runCurrent()
        assertTrue(holder.state.value.isScanning)
        assertNull(holder.state.value.outcome)

        newResult.complete(WifiScanOutcome.WifiDisabled)
        runCurrent()
        assertEquals(WifiScanOutcome.WifiDisabled, holder.state.value.outcome)
        assertFalse(holder.state.value.isScanning)
    }

    @Test
    fun closingPanel_cancelsScanAndClearsResult() = runTest {
        var cancelled = false
        val holder = WifiScanStateHolder(backgroundScope, {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }, strings)

        holder.refresh()
        runCurrent()
        holder.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(WifiScanUiState(), holder.state.value)
    }

    @Test
    fun scanFailure_clearsLoadingAndAllowsRetry() = runTest {
        var requests = 0
        val holder = WifiScanStateHolder(backgroundScope, {
            if (++requests == 1) error("scan unavailable")
            WifiScanOutcome.NoResults
        }, strings)

        holder.refresh()
        runCurrent()
        assertEquals(
            WifiScanOutcome.Failure("msg:${R.string.wifi_scan_failed}"),
            holder.state.value.outcome
        )
        assertFalse(holder.state.value.isScanning)
        holder.refresh()
        runCurrent()
        assertEquals(WifiScanOutcome.NoResults, holder.state.value.outcome)
    }
}
