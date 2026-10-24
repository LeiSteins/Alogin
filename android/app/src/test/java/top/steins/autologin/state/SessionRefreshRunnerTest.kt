package top.steins.autologin.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRefreshRunnerTest {
    @Test
    fun refresh_waitsForPreparationThenRetriesAtConfiguredInterval() = runTest {
        val runner = SessionRefreshRunner(backgroundScope)
        val ready = CompletableDeferred<Unit>()
        val attempts = mutableListOf<Int>()
        runner.start(3, 100, 200, { ready.await() }, {}, {}) { _, attempt ->
            attempts += attempt
            attempt == 1
        }
        runCurrent()
        advanceTimeBy(1_000)
        assertTrue(attempts.isEmpty())
        ready.complete(Unit)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(0), attempts)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(0, 1), attempts)
        advanceTimeBy(1_000)
        assertEquals(listOf(0, 1), attempts)
    }

    @Test
    fun replacement_cancelsOldAttemptAndAssignsNewGeneration() = runTest {
        val runner = SessionRefreshRunner(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val finished = mutableListOf<Long>()
        runner.start(1, 0, 0, {}, {}, finished::add) { _, _ -> gate.await(); true }
        runCurrent()
        val oldGeneration = runner.generation
        var completed = false
        runner.start(1, 0, 0, {}, {}, finished::add) { _, _ -> completed = true; true }
        runCurrent()
        assertTrue(completed)
        assertTrue(runner.generation > oldGeneration)
        assertTrue(oldGeneration in finished)
    }

    @Test
    fun loginPriority_cancelsBothDebounceAndRetriesBeforeReturning() = runTest {
        val runner = SessionRefreshRunner(backgroundScope)
        var debounced = false
        var attempted = false
        var finished = false
        runner.debounce(250) { debounced = true }
        runner.start(2, 500, 500, {}, {}, { finished = true }) { _, _ -> attempted = true; false }
        runCurrent()
        val previous = runner.generation
        runner.cancelForLogin()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(finished)
        assertFalse(debounced)
        assertFalse(attempted)
        assertTrue(runner.generation > previous)
    }

    @Test
    fun immediateCompletion_scheduledConfirmationRemainsCancellableByLogin() =
        runTest(UnconfinedTestDispatcher()) {
            val runner = SessionRefreshRunner(backgroundScope)
            var confirmationCancelled = false
            runner.start(1, 0, 0, {}, {}, {}) { _, _ ->
                runner.start(1, 0, 0, {}, {}, {}) { _, _ ->
                    try {
                        awaitCancellation()
                    } finally {
                        confirmationCancelled = true
                    }
                }
                true
            }

            runner.cancelForLogin()

            assertTrue(confirmationCancelled)
        }

    @Test
    fun debounce_burstRunsOnlyLatestRefresh() = runTest {
        val runner = SessionRefreshRunner(this)
        val events = mutableListOf<String>()
        runner.debounce(250) { events += "old" }
        advanceTimeBy(100)
        runner.debounce(250) { events += "latest" }
        advanceUntilIdle()
        assertEquals(listOf("latest"), events)
    }
}
