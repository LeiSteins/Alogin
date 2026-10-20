package top.steins.autologin.data

interface UpdateCheckStore {
    fun getLastUpdateCheckAt(): Long
    fun setLastUpdateCheckAt(timestampMillis: Long)
    fun getUpdateCheckRetryAt(): Long
    fun setUpdateCheckRetryAt(timestampMillis: Long)
}
