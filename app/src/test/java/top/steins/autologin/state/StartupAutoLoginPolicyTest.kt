package top.steins.autologin.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupAutoLoginPolicyTest {
    @Test
    fun disabledAtStartup_enablingLaterDoesNotCreateOpportunity() {
        assertFalse(StartupAutoLoginPolicy(false).consume(true, true, true))
    }

    @Test
    fun successfulEligibility_canBeConsumedOnlyOnce() {
        val policy = StartupAutoLoginPolicy(true)
        assertTrue(policy.consume(true, true, true))
        assertFalse(policy.consume(true, true, true))
    }

    @Test
    fun missingPrerequisite_consumesOpportunityWithoutDeferredLogin() {
        for (conditions in listOf(Triple(false, true, true), Triple(true, false, true), Triple(true, true, false))) {
            val policy = StartupAutoLoginPolicy(true)
            assertFalse(policy.consume(conditions.first, conditions.second, conditions.third))
            assertFalse(policy.consume(true, true, true))
        }
    }

    @Test
    fun cancellation_preventsLoginEvenAfterSettingIsReenabled() {
        val policy = StartupAutoLoginPolicy(true)
        policy.cancel()
        assertFalse(policy.consume(true, true, true))
    }
}
