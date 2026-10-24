package top.steins.autologin.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.steins.autologin.AppStrings
import top.steins.autologin.domain.model.LoginStatus
import top.steins.autologin.domain.model.SeamlessAction
import top.steins.autologin.domain.model.SeamlessActionResult
import top.steins.autologin.domain.model.SeamlessInfo
import top.steins.autologin.domain.model.SeamlessLoadResult
import top.steins.autologin.domain.model.SeamlessStatus
import top.steins.autologin.domain.model.WifiLoginMethod
import top.steins.autologin.domain.port.LoginStatusReader
import top.steins.autologin.domain.port.SeamlessGateway

@OptIn(ExperimentalCoroutinesApi::class)
class SeamlessCoordinatorTest {
    private val strings = AppStrings { resource, _ -> "msg:$resource" }
    private val session = TestSessionAccess()
    private val service = TestSelfService()
    private val network = TestLoginStatusReader()

    @Test
    fun query_reportsConfirmedStatusForCanonicalAccountWithoutChangingSnapshot() = runTest {
        val snapshot = session.current
        service.loadResult = SeamlessLoadResult.Success(
            SeamlessInfo("2021001@bjut", 1, SeamlessStatus.Enabled)
        )
        val coordinator = SeamlessCoordinator(
            backgroundScope, strings, service, network, session, { null }, {}
        )

        coordinator.refresh()
        runCurrent()

        assertEquals("2021001", service.requestedUsername)
        assertEquals(listOf(snapshot to SeamlessStatus.Enabled), session.reports)
        assertEquals(snapshot, session.current)
        assertEquals(SeamlessStatus.Enabled, coordinator.state.value.info?.status)
    }

    @Test
    fun query_sessionChangesWhileWaitingForExecution_doesNotStartRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        session.executionGate = gate
        val coordinator = SeamlessCoordinator(
            backgroundScope, strings, service, network, session, { null }, {}
        )

        coordinator.refresh()
        runCurrent()
        session.current = session.current.copy(version = 2)
        gate.complete(Unit)
        runCurrent()

        assertEquals(0, service.loadCount)
        assertTrue(session.reports.isEmpty())
        assertFalse(coordinator.state.value.isLoading)
    }

    @Test
    fun query_sameAccountMovesToAnotherNetworkBeforeResult_oldResultIsDiscarded() = runTest {
        val gate = CompletableDeferred<Unit>()
        service.loadGate = gate
        val coordinator = SeamlessCoordinator(
            backgroundScope, strings, service, network, session, { null }, {}
        )

        coordinator.refresh()
        runCurrent()
        session.current = session.current.copy(version = 2, ipAddress = "10.1.2.99")
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, service.loadCount)
        assertTrue(session.reports.isEmpty())
        assertNull(coordinator.state.value.info)
        assertFalse(coordinator.state.value.isLoading)
    }

    @Test
    fun mutation_cachedInfoIsRefreshing_rejectsSubmissionUntilReadCompletes() = runTest {
        val coordinator = SeamlessCoordinator(
            backgroundScope, strings, service, network, session, { null }, {}
        )
        coordinator.refresh()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        service.loadGate = gate
        coordinator.refresh()
        runCurrent()

        assertTrue(coordinator.state.value.canPrepareAction)
        val action = SeamlessAction.Toggle(SeamlessStatus.Enabled)
        coordinator.perform(action)
        runCurrent()
        assertEquals(0, service.actionCount)

        gate.complete(Unit)
        runCurrent()
        coordinator.perform(action)
        runCurrent()
        assertEquals(1, service.actionCount)
    }

    @Test
    fun mutation_sessionChangesDuringAuthentication_doesNotSubmit() = runTest {
        val coordinator = SeamlessCoordinator(
            backgroundScope, strings, service, network, session, { null }, {}
        )
        coordinator.refresh()
        runCurrent()
        session.reports.clear()
        val gate = CompletableDeferred<Unit>()
        network.authenticationGate = gate

        coordinator.perform(SeamlessAction.Bind("AABBCC112233"))
        runCurrent()
        session.current = session.current.copy(version = 2, username = "another-account")
        gate.complete(Unit)
        runCurrent()

        assertEquals(0, service.actionCount)
        assertTrue(session.reports.isEmpty())
        assertFalse(coordinator.state.value.isSubmitting)
    }

    @Test
    fun mutation_sessionChangesBeforeResponse_doesNotPublishOrReloadOldResult() = runTest {
        val coordinator = SeamlessCoordinator(
            backgroundScope, strings, service, network, session, { null }, {}
        )
        coordinator.refresh()
        runCurrent()
        session.reports.clear()
        val gate = CompletableDeferred<Unit>()
        service.actionGate = gate

        coordinator.perform(SeamlessAction.Bind("AABBCC112233"))
        runCurrent()
        session.current = session.current.copy(version = 2)
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, service.actionCount)
        assertEquals(1, service.loadCount)
        assertTrue(session.reports.isEmpty())
        assertEquals("", coordinator.state.value.operationMessage)
        assertFalse(coordinator.state.value.isSubmitting)
    }
}

private class TestSessionAccess : SeamlessSessionAccess {
    var current = CampusSessionSnapshot(1, "2021001", "bjut_wifi", "10.1.2.3", true, false)
    var executionGate: CompletableDeferred<Unit>? = null
    val reports = mutableListOf<Pair<CampusSessionSnapshot, SeamlessStatus>>()

    override fun snapshot() = current
    override fun isCurrent(expected: CampusSessionSnapshot) = expected == current

    override suspend fun withSession(expected: CampusSessionSnapshot, operation: suspend () -> Unit) {
        executionGate?.await()
        if (isCurrent(expected)) operation()
    }

    override fun reportStatus(expected: CampusSessionSnapshot, status: SeamlessStatus) {
        reports += expected to status
    }
}

private class TestSelfService : SeamlessGateway {
    var loadGate: CompletableDeferred<Unit>? = null
    var actionGate: CompletableDeferred<Unit>? = null
    var loadCount = 0
    var actionCount = 0
    var requestedUsername: String? = null
    var loadResult: SeamlessLoadResult = SeamlessLoadResult.Success(
        SeamlessInfo("2021001", 1, SeamlessStatus.Enabled)
    )

    override suspend fun loadSeamlessInfo(username: String, includeDevices: Boolean): SeamlessLoadResult {
        loadCount++
        requestedUsername = username
        loadGate?.await()
        return loadResult
    }

    override suspend fun performSeamlessAction(info: SeamlessInfo, action: SeamlessAction): SeamlessActionResult {
        actionCount++
        actionGate?.await()
        return SeamlessActionResult.Success
    }
}

private class TestLoginStatusReader : LoginStatusReader {
    var authenticationGate: CompletableDeferred<Unit>? = null

    override suspend fun fetchLoginStatus(knownLoggedIn: Boolean, preferredMethod: WifiLoginMethod?): LoginStatus {
        authenticationGate?.await()
        return LoginStatus(isLoggedIn = true, uid = "2021001")
    }
}
