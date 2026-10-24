package top.steins.autologin.network.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateRepositoryTest {
    private fun manifest() = JSONObject()
        .put("schemaVersion", 1)
        .put("version", "0.2.0")
        .put("versionCode", 20)
        .put("fileName", "alogin-v0.2.0.apk")
        .put("releaseNotes", "- 新增更新说明\n- 修复\"登录\"问题")

    @Test
    fun parseLatestUpdate_readsVersionAndChineseReleaseNotes() {
        val update = parseLatestUpdate(manifest().toString())
        assertEquals("0.2.0", update?.version)
        assertEquals(20, update?.versionCode)
        assertEquals("alogin-v0.2.0.apk", update?.fileName)
        assertEquals("https://aloginupdate.steins.top/alogin-v0.2.0.apk", update?.downloadUrl)
        assertEquals("- 新增更新说明\n- 修复\"登录\"问题", update?.releaseNotes)
    }

    @Test
    fun parseLatestUpdate_rejectsInvalidOrUnsafeMetadata() {
        val invalidFields = listOf(
            "schemaVersion" to 2,
            "schemaVersion" to "1",
            "version" to "0.2",
            "version" to "0.2.0-01",
            "version" to "v0.2.0",
            "version" to " 0.2.0 ",
            "versionCode" to 0,
            "versionCode" to "20",
            "versionCode" to 20.5,
            "fileName" to "../alogin-v0.2.0.apk",
            "fileName" to "https://example.com/alogin-v0.2.0.apk",
            "fileName" to "alogin-v0.3.0.apk",
            "releaseNotes" to "  ",
            "releaseNotes" to true,
            "releaseNotes" to "a".repeat(20_001)
        )
        invalidFields.forEach { (key, value) ->
            assertNull(key, parseLatestUpdate(manifest().put(key, value).toString()))
        }
    }

    @Test
    fun parseLatestUpdate_rejectsMissingFieldsAndMalformedResponses() {
        listOf("schemaVersion", "version", "versionCode", "fileName", "releaseNotes").forEach { key ->
            val json = manifest().apply { remove(key) }
            assertNull(key, parseLatestUpdate(json.toString()))
        }
        listOf("", "[]", "null", "{", "<a href='alogin-v0.2.0.apk'>download</a>").forEach {
            assertNull(parseLatestUpdate(it))
        }
    }

    @Test
    fun parseLatestUpdate_acceptsPrereleaseAndIgnoresUnknownFields() {
        val json = manifest()
            .put("version", "0.2.0-rc.1+build.2")
            .put("fileName", "alogin-v0.2.0-rc.1+build.2.apk")
            .put("downloadUrl", "https://example.com/untrusted.apk")
        assertEquals(
            "https://aloginupdate.steins.top/alogin-v0.2.0-rc.1+build.2.apk",
            parseLatestUpdate(json.toString())?.downloadUrl
        )
    }
}
