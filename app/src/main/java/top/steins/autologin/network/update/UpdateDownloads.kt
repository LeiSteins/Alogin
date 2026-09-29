package top.steins.autologin.network.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.steins.autologin.BuildConfig
import top.steins.autologin.R

/** 所有文件和 DownloadManager 操作在 IO 线程串行执行，重复点击不会创建重复任务。 */
internal class UpdateDownloads(
    private val context: Context,
    private val installedVersionCode: Int = BuildConfig.VERSION_CODE
) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences("alogin_updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var verifiedFile: Pair<String, Pair<Long, Long>>? = null

    suspend fun start(update: UpdateInfo): UpdateTransfer = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                // 同样约束恢复的数据与调用方数据，文件名不得逃出专属目录。
                require(parseLatestUpdate(metadata(update)) == update)
                val existing = readRecord()
                if (existing?.update == update) {
                    val state = query(existing)
                    if (state is UpdateDownloadState.Downloading || state == UpdateDownloadState.Ready) {
                        return@withLock UpdateTransfer(update, state)
                    }
                }
                if (existing != null) removeRecord(existing)
                val file = destination(update)
                if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw IOException()
                // 处理进程在入队、落盘之间退出后留下的完整文件。
                if (isValid(file, update, force = true)) {
                    saveRecord(Record(update, -1L))
                    return@withLock UpdateTransfer(update, UpdateDownloadState.Ready)
                }
                if (file.exists() && !file.delete()) throw IOException()
                saveRecord(Record(update, -1L))
                val request = DownloadManager.Request(update.downloadUrl.toUri()).apply {
                    setTitle(update.fileName)
                    setDescription(context.getString(R.string.update_download_description))
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(false)
                    setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, "updates/${update.fileName}"
                    )
                    setMimeType(UpdateInstaller.APK_MIME_TYPE)
                }
                val id = manager.enqueue(request)
                try {
                    saveRecord(Record(update, id))
                } catch (error: Exception) {
                    manager.remove(id)
                    throw error
                }
                UpdateTransfer(update, UpdateDownloadState.Downloading())
            } catch (_: IOException) {
                UpdateTransfer(update, UpdateDownloadState.Failed(UpdateDownloadFailure.STORAGE))
            } catch (_: Exception) {
                UpdateTransfer(update, UpdateDownloadState.Failed(UpdateDownloadFailure.DOWNLOAD))
            }
        }
    }

    suspend fun refresh(): UpdateTransfer? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = readRecord() ?: return@withLock null
            if (record.update.versionCode <= installedVersionCode) {
                // 安装成功后的首次启动才清理，安装器读取 APK 期间始终保留文件。
                runCatching { removeRecord(record) }
                return@withLock null
            }
            val state = runCatching { query(record) }.getOrElse {
                UpdateDownloadState.Failed(UpdateDownloadFailure.DOWNLOAD)
            }
            UpdateTransfer(record.update, state)
        }
    }

    suspend fun validatedFile(update: UpdateInfo): File? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val record = readRecord() ?: return@runCatching null
                if (record.update != update || query(record) != UpdateDownloadState.Ready) return@runCatching null
                destination(update).takeIf { isValid(it, update, force = true) }
            }.getOrNull()
        }
    }

    /** 仅在前台订阅；广播用于及时刷新，轮询补充进度及遗漏的完成通知。 */
    fun changes(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE &&
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2L) ==
                    preferences.getLong("download_id", -1L)
                ) trySend(Unit)
            }
        }
        // DownloadProvider 属于另一个系统 UID；广播只触发查询，不直接信任其结果。
        val registered = runCatching {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
        }.isSuccess
        val ticker = launch {
            while (true) {
                send(Unit)
                delay(1_000)
            }
        }
        awaitClose {
            ticker.cancel()
            if (registered) context.unregisterReceiver(receiver)
        }
    }.buffer(Channel.CONFLATED)

    private fun query(record: Record): UpdateDownloadState {
        if (record.id >= 0) {
            manager.query(DownloadManager.Query().setFilterById(record.id))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                    when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PAUSED -> {
                            val total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            val downloaded = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val reason = number(DownloadManager.COLUMN_REASON).toInt()
                            return UpdateDownloadState.Downloading(
                                progress = if (total > 0) ((downloaded.toDouble() / total) * 100).toInt().coerceIn(0, 100) else null,
                                waitingForNetwork = reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK ||
                                    reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI ||
                                    reason == DownloadManager.PAUSED_WAITING_TO_RETRY
                            )
                        }
                        DownloadManager.STATUS_FAILED -> return UpdateDownloadState.Failed(
                            if (number(DownloadManager.COLUMN_REASON).toInt() in setOf(
                                    DownloadManager.ERROR_INSUFFICIENT_SPACE, DownloadManager.ERROR_DEVICE_NOT_FOUND,
                                    DownloadManager.ERROR_FILE_ERROR
                                )) UpdateDownloadFailure.STORAGE else UpdateDownloadFailure.DOWNLOAD
                        )
                        DownloadManager.STATUS_SUCCESSFUL -> Unit
                        else -> return UpdateDownloadState.Failed(UpdateDownloadFailure.DOWNLOAD)
                    }
                }
            }
        }
        val file = destination(record.update)
        return when {
            !file.isFile -> UpdateDownloadState.Failed(UpdateDownloadFailure.MISSING_FILE)
            !isValid(file, record.update) -> UpdateDownloadState.Failed(UpdateDownloadFailure.INVALID_APK)
            else -> UpdateDownloadState.Ready
        }
    }

    @Suppress("DEPRECATION")
    private fun isValid(file: File, update: UpdateInfo, force: Boolean = false): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val fingerprint = file.absolutePath to (file.length() to file.lastModified())
        if (!force && verifiedFile == fingerprint) return true
        verifiedFile = null
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        val valid = matchesUpdateArchive(
            archive.packageName, archive.versionName, PackageInfoCompat.getLongVersionCode(archive),
            context.packageName, installedVersionCode.toLong(), update
        )
        if (valid) verifiedFile = fingerprint
        return valid
    }

    private fun destination(update: UpdateInfo): File {
        require(parseLatestUpdate(metadata(update)) == update)
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException()
        val directory = File(base, "updates").canonicalFile
        return File(directory, update.fileName).canonicalFile.also {
            require(it.parentFile == directory)
        }
    }

    private fun readRecord(): Record? {
        val json = preferences.getString("metadata", null) ?: return null
        val update = parseLatestUpdate(json) ?: return null
        return Record(update, preferences.getLong("download_id", -1L))
    }

    private fun saveRecord(record: Record) {
        if (!preferences.edit().putString("metadata", metadata(record.update))
                .putLong("download_id", record.id).commit()) throw IOException()
    }

    private fun removeRecord(record: Record) {
        if (record.id >= 0) manager.remove(record.id)
        val file = destination(record.update)
        if (file.exists() && !file.delete()) throw IOException()
        verifiedFile = null
        if (!preferences.edit().clear().commit()) throw IOException()
    }

    private data class Record(val update: UpdateInfo, val id: Long)
}

private fun metadata(update: UpdateInfo): String = JSONObject()
    .put("schemaVersion", 1).put("version", update.version).put("versionCode", update.versionCode)
    .put("fileName", update.fileName).put("releaseNotes", update.releaseNotes).toString()
