package top.steins.autologin.domain.port

import java.io.File
import kotlinx.coroutines.flow.Flow
import top.steins.autologin.domain.model.UpdateInfo
import top.steins.autologin.domain.model.UpdateTransfer

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
