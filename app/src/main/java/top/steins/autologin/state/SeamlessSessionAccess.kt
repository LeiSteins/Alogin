package top.steins.autologin.state

import top.steins.autologin.domain.model.SeamlessStatus

/** 无感知功能所需的只读会话信息，不暴露主页状态或同步原语。 */
internal data class CampusSessionSnapshot(
    val version: Long,
    val username: String,
    val wifiName: String,
    val ipAddress: String,
    val isOnline: Boolean,
    val isRefreshing: Boolean
)

/** 会话协调器负责串行执行、校验快照，以及将确认结果写回主页。 */
internal interface SeamlessSessionAccess {
    fun snapshot(): CampusSessionSnapshot
    fun isCurrent(expected: CampusSessionSnapshot): Boolean

    /** 获得会话执行权后重新校验快照；过期操作不执行。 */
    suspend fun withSession(expected: CampusSessionSnapshot, operation: suspend () -> Unit)

    /** 只接收当前会话的结果；Unknown 表示认证复核发现会话失效。 */
    fun reportStatus(expected: CampusSessionSnapshot, status: SeamlessStatus)
}
