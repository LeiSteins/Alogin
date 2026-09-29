package top.steins.autologin.network.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateArchiveTest {
    private val update = UpdateInfo("0.2.0", "alogin-v0.2.0.apk", "", 20)

    @Test
    fun archive_mustMatchPackageAndBothVersionFields() {
        assertTrue(matchesUpdateArchive("app", "0.2.0", 20, "app", 19, update))
        assertFalse(matchesUpdateArchive("other.app", "0.2.0", 20, "app", 19, update))
        assertFalse(matchesUpdateArchive("app", "0.1.9", 20, "app", 19, update))
        assertFalse(matchesUpdateArchive("app", "0.2.0", 21, "app", 19, update))
        assertFalse(matchesUpdateArchive(null, null, 0, "app", 19, update))
    }

    @Test
    fun archive_rejectsReinstallAndDowngrade() {
        assertFalse(matchesUpdateArchive("app", "0.2.0", 20, "app", 20, update))
        assertFalse(matchesUpdateArchive("app", "0.2.0", 20, "app", 21, update))
    }
}
