package top.steins.autologin.network.update

import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installIntent_grantsReadAccessOnlyToUpdateDirectory() {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
        directory.mkdirs()
        val file = File.createTempFile("provider-test-", ".apk", directory)
        try {
            file.writeText("test")
            val intent = UpdateInstaller(context).installIntent(file)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertEquals("${context.packageName}.updates", intent.data!!.authority)
            assertEquals(UpdateInstaller.APK_MIME_TYPE, intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
            assertEquals("test", context.contentResolver.openInputStream(intent.data!!)!!.bufferedReader().use { it.readText() })
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun provider_rejectsFilesOutsideUpdateDirectory() {
        FileProvider.getUriForFile(
            context, "${context.packageName}.updates",
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "outside.apk")
        )
    }

    @Test
    fun permissionIntent_targetsOnlyThisApp() {
        val intent = UpdateInstaller(context).permissionIntent()
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }
}
