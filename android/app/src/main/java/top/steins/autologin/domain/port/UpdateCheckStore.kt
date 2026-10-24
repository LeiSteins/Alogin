package top.steins.autologin.domain.port

interface UpdateCheckStore {
    fun getLastUpdateCheckAt(): Long
    fun setLastUpdateCheckAt(timestampMillis: Long)
    fun getUpdateCheckRetryAt(): Long
    fun setUpdateCheckRetryAt(timestampMillis: Long)
}
