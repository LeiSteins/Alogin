package top.steins.autologin.network.update

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.steins.autologin.BuildConfig

class UpdateDownloadsTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testId = "update-test-${UUID.randomUUID()}"
    private val root = File(target.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), testId)
    private val context = object : ContextWrapper(target) {
        override fun getExternalFilesDir(type: String?): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(testId, mode)
    }
    private val preferences = context.getSharedPreferences("alogin_updates", Context.MODE_PRIVATE)
    private val update = UpdateInfo(
        BuildConfig.VERSION_NAME, "alogin-v${BuildConfig.VERSION_NAME}.apk",
        "https://aloginupdate.steins.top/alogin-v${BuildConfig.VERSION_NAME}.apk",
        BuildConfig.VERSION_CODE, "测试更新"
    )

    private fun record() {
        val metadata = JSONObject().put("schemaVersion", 1).put("version", update.version)
            .put("versionCode", update.versionCode).put("fileName", update.fileName)
            .put("releaseNotes", update.releaseNotes)
        assertTrue(preferences.edit().putString("metadata", metadata.toString()).putLong("download_id", -1).commit())
    }

    private fun copyApk(): File = File(root, "updates/${update.fileName}").also {
        it.parentFile!!.mkdirs()
        File(target.applicationInfo.sourceDir).copyTo(it, overwrite = true)
    }

    @After
    fun cleanUp() {
        target.deleteSharedPreferences(testId)
        root.deleteRecursively()
    }

    @Test
    fun completedApk_isRestoredReusedAndRevalidatedBeforeInstallation() = runBlocking {
        val file = copyApk()
        record()
        val downloads = UpdateDownloads(context, installedVersionCode = 0)
        assertEquals(UpdateDownloadState.Ready, downloads.refresh()!!.state)
        assertEquals(UpdateDownloadState.Ready, downloads.start(update).state)
        assertEquals(-1L, preferences.getLong("download_id", -2))
        assertEquals(file.canonicalFile, downloads.validatedFile(update))
        assertEquals(UpdateDownloadState.Ready, UpdateDownloads(context, 0).refresh()!!.state)
        file.writeText("damaged APK")
        assertNull(downloads.validatedFile(update))
        assertEquals(UpdateDownloadState.Failed(UpdateDownloadFailure.INVALID_APK), downloads.refresh()!!.state)
    }

    @Test
    fun missingFile_isRecoverableFailureWithMetadataPreserved() = runBlocking {
        record()
        val transfer = UpdateDownloads(context, 0).refresh()!!
        assertEquals(update, transfer.update)
        assertEquals(UpdateDownloadState.Failed(UpdateDownloadFailure.MISSING_FILE), transfer.state)
        assertNotNull(preferences.getString("metadata", null))
    }

    @Test
    fun firstLaunchAfterUpgrade_removesOnlyRecordedUpdate() = runBlocking {
        val file = copyApk()
        val unrelated = File(root, "unrelated.txt").apply { writeText("keep") }
        record()
        assertNull(UpdateDownloads(context).refresh())
        assertFalse(file.exists())
        assertTrue(unrelated.exists())
        assertFalse(preferences.contains("metadata"))
    }

    @Test
    fun untrustedFilename_cannotWriteOutsideUpdateDirectory() = runBlocking {
        val unsafe = update.copy(fileName = "../escape.apk")
        assertTrue(UpdateDownloads(context, 0).start(unsafe).state is UpdateDownloadState.Failed)
        assertFalse(File(root, "escape.apk").exists())
        assertFalse(preferences.contains("metadata"))
    }
}
