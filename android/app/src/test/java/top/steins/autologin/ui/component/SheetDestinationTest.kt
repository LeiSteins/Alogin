package top.steins.autologin.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetDestinationTest {
    @Test
    fun releaseProjectsVelocityBeforeChoosingADetentOrDismissal() {
        assertEquals(0.5625f, sheetDestination(0.50f, 0f, 0.5625f, 0.9f))
        assertEquals(0f, sheetDestination(0.26f, 0f, 0.5625f, 0.9f))
        assertEquals(0f, sheetDestination(0.5f, -3f, 0.5625f, 0.9f))
        assertEquals(0.9f, sheetDestination(0.6f, 2f, 0.5625f, 0.9f))
    }

    @Test
    fun shortContentAndKeyboardUseTheirResolvedAnchors() {
        assertEquals(0.65f, sheetDestination(0.64f, 0f, 0.5625f, 0.65f))
        assertEquals(0.4f, sheetDestination(0.35f, 0f, 0.4f, 0.4f))
    }
}
