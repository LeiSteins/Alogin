package top.steins.autologin.network.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import top.steins.autologin.network.executeCancellable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val UPDATE_BASE_URL = "https://aloginupdate.steins.top/"

data class UpdateInfo(
    val version: String,
    val fileName: String,
    val downloadUrl: String,
    val versionCode: Int,
    val releaseNotes: String = ""
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latestVersion: String) : UpdateState
    data class Available(
        val update: UpdateInfo,
        val download: UpdateDownloadState = UpdateDownloadState.None
    ) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * 应用更新检查与下载的入口抽象，便于 ViewModel 单元测试注入替身。
 */
interface UpdateGateway {
    suspend fun fetchLatestUpdate(currentVersion: String): UpdateInfo

    suspend fun downloadUpdate(update: UpdateInfo): UpdateTransfer
    suspend fun refreshDownload(): UpdateTransfer?
    fun observeDownloadChanges(): Flow<Unit>
    suspend fun validatedDownload(update: UpdateInfo): File?
}

class UpdateRepository(context: Context) : UpdateGateway {

    private val downloads = UpdateDownloads(context.applicationContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun fetchLatestUpdate(currentVersion: String): UpdateInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(UPDATE_BASE_URL + "latest.json")
            .header("User-Agent", "Alogin $currentVersion")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        client.executeCancellable(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("更新服务器返回 HTTP ${response.code}")
            }
            parseLatestUpdate(response.body.string())
                ?: throw IOException("更新服务器未提供有效的版本信息")
        }
    }

    override suspend fun downloadUpdate(update: UpdateInfo): UpdateTransfer = downloads.start(update)
    override suspend fun refreshDownload(): UpdateTransfer? = downloads.refresh()
    override fun observeDownloadChanges(): Flow<Unit> = downloads.changes()
    override suspend fun validatedDownload(update: UpdateInfo): File? = downloads.validatedFile(update)
}

/** 下载地址由可信服务器和受限文件名组成，不接受清单指定的任意 URL。 */
internal fun parseLatestUpdate(json: String): UpdateInfo? = runCatching {
    val manifest = JSONObject(json)
    require(manifest.opt("schemaVersion") == 1)
    val version = manifest.opt("version") as? String ?: return null
    require(version == version.trim() && !version.startsWith("v"))
    require(SemanticVersion.parseOrNull(version) != null)
    val versionCode = manifest.opt("versionCode") as? Int ?: return null
    require(versionCode in 1..2_100_000_000)
    val fileName = manifest.opt("fileName") as? String ?: return null
    require(fileName == "alogin-v$version.apk")
    val releaseNotes = (manifest.opt("releaseNotes") as? String ?: return null).trim()
    require(releaseNotes.isNotEmpty() && releaseNotes.length <= 20_000)
    UpdateInfo(
        version = version,
        fileName = fileName,
        downloadUrl = UPDATE_BASE_URL + fileName,
        versionCode = versionCode,
        releaseNotes = releaseNotes
    )
}.getOrNull()
