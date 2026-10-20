package top.steins.autologin.network

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelfServiceSessionTest {
    private var route = "wifi-1:10.1.2.3"
    private var opens = 0
    private var cookieClears = 0
    private var closes = 0
    private var opening: CompletableDeferred<Unit>? = null
    private val session = SelfServiceSession(
        awaitRoute = { route },
        isCurrent = { it == route },
        openSession = { _: String, _: String -> opens += 1; opening?.await(); Unit },
        clearCookies = { cookieClears += 1 },
        closeResources = { closes += 1 }
    )

    @Test
    fun reads_reuseSessionForCanonicalAccountWithoutLoadingOverview() = runTest {
        val first = session.withSession("2021001@bjut") { it }
        val second = session.withSession(" 2021001 ") { it }
        assertEquals(first, second)
        assertEquals("2021001", second.account)
        assertEquals(1, opens)
    }

    @Test
    fun overviewFailure_keepsEstablishedSessionAvailableToOtherFeatures() = runTest {
        try {
            session.withSession("2021001") { throw IOException("dashboard unavailable") }
            fail("Expected dashboard failure")
        } catch (_: IOException) {
            // A feature response failure does not invalidate the authenticated Cookie session.
        }
        session.withSession("2021001") { assertEquals("2021001", it.account) }
        assertEquals(1, opens)
    }

    @Test
    fun accountChange_rejectsOldMutationWithoutInvalidatingNewSession() = runTest {
        val first = session.withSession("first") { it }
        val second = session.withSession("second") { it }
        assertNotEquals(first.generation, second.generation)
        expectUnavailable(SelfServiceSessionFailure.EXPIRED) {
            session.withExistingSession("first", first.generation) { fail("Old mutation ran") }
        }
        session.withExistingSession("second", second.generation) { assertEquals(second, it) }
        assertEquals(2, opens)
    }

    @Test
    fun forcedRenewal_rejectsPreviouslyLoadedMutationEvenForSameAccount() = runTest {
        val first = session.withSession("account") { it }
        session.withSession("account", forceRenew = true) { }
        expectUnavailable(SelfServiceSessionFailure.EXPIRED) {
            session.withExistingSession("account", first.generation) { fail("Stale mutation ran") }
        }
        assertEquals(2, opens)
    }

    @Test
    fun routeChangesDuringSso_neverStartsFeatureRequest() = runTest {
        opening = CompletableDeferred()
        val request = async {
            expectUnavailable(SelfServiceSessionFailure.EXPIRED) {
                session.withSession("account") { fail("Request ran on old route") }
            }
        }
        runCurrent()
        route = "wifi-2:10.2.3.4"
        opening!!.complete(Unit)
        request.await()
        session.withSession("account") { assertEquals(route, it.route) }
        assertEquals(2, opens)
    }

    @Test
    fun networkChangesWhileMutationWaitsForLock_neverSubmitsOldMutation() = runTest {
        val handle = session.withSession("account") { it }
        val gate = CompletableDeferred<Unit>()
        val read = launch {
            expectUnavailable(SelfServiceSessionFailure.EXPIRED) {
                session.withSession("account") { gate.await() }
            }
        }
        runCurrent()
        val mutation = async {
            expectUnavailable(SelfServiceSessionFailure.EXPIRED) {
                session.withExistingSession("account", handle.generation) { fail("Old mutation ran") }
            }
        }
        runCurrent()
        route = "wifi-2:10.2.3.4"
        gate.complete(Unit)
        read.join()
        mutation.await()
    }

    @Test
    fun cancellationDuringSso_clearsPartialCookiesAndAllowsRetry() = runTest {
        opening = CompletableDeferred()
        val request = launch { session.withSession("account") { fail("Cancelled SSO continued") } }
        runCurrent()
        val clearsBeforeCancellation = cookieClears
        request.cancelAndJoin()
        assertTrue(cookieClears > clearsBeforeCancellation)
        opening = null
        session.withSession("account") { }
        assertEquals(2, opens)
    }

    @Test
    fun submittedMutation_disconnectsCurrentRouteAndPreservesIndeterminateResult() = runTest {
        val handle = session.withSession("account") { it }
        val result = session.withExistingSession("account", handle.generation) {
            route = "disconnected"
            "indeterminate"
        }
        assertEquals("indeterminate", result)
        expectUnavailable(SelfServiceSessionFailure.EXPIRED) {
            session.withExistingSession("account", handle.generation) { fail("Disconnected session reused") }
        }
    }

    @Test
    fun clearSession_invalidatesPreviouslyLoadedMutation() = runTest {
        val handle = session.withSession("account") { it }
        session.clear()
        expectUnavailable(SelfServiceSessionFailure.EXPIRED) {
            session.withExistingSession("account", handle.generation) { fail("Cleared mutation ran") }
        }
    }

    @Test
    fun close_isIdempotentAndPreventsReopening() = runTest {
        session.withSession("account") { }
        session.close()
        session.close()
        expectUnavailable(SelfServiceSessionFailure.EXPIRED) {
            session.withSession("account") { fail("Closed session reopened") }
        }
        assertEquals(1, opens)
        assertEquals(1, closes)
    }

    private suspend fun expectUnavailable(
        reason: SelfServiceSessionFailure,
        operation: suspend () -> Unit
    ) {
        try {
            operation()
            fail("Expected $reason")
        } catch (error: SelfServiceSessionException) {
            assertEquals(reason, error.reason)
        }
    }
}
