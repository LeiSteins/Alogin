package top.steins.autologin.domain.rule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun isNewer_comparesStableVersionsNumerically() {
        assertTrue(SemanticVersion.isNewer("0.10.0", "0.9.9"))
        assertTrue(SemanticVersion.isNewer("1.0.1", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("0.1.0", "0.1.0"))
        assertFalse(SemanticVersion.isNewer("0.0.9", "0.1.0"))
    }

    @Test
    fun parse_supportsPrereleaseAndBuildMetadata() {
        assertTrue(SemanticVersion.isNewer("1.0.0", "1.0.0-rc.1"))
        assertTrue(SemanticVersion.isNewer("1.0.0-rc.2", "1.0.0-rc.1"))
        assertFalse(SemanticVersion.isNewer("1.0.0+build.2", "1.0.0+build.1"))
        assertNotNull(SemanticVersion.parseOrNull("v2.3.4-beta.1+build.8"))
    }

    @Test
    fun parse_rejectsInvalidVersions() {
        assertNull(SemanticVersion.parseOrNull("1.2"))
        assertNull(SemanticVersion.parseOrNull("01.2.3"))
        assertNull(SemanticVersion.parseOrNull("1.2.3-rc.01"))
        assertNull(SemanticVersion.parseOrNull("latest"))
    }
}
