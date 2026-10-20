package top.steins.autologin.domain.model

enum class HttpLogEntryType {
    HTTP_REQUEST,
    ACCOUNT_INFO_REFRESH
}

data class HttpLogEntry(
    val id: Long,
    val method: String,
    val url: String,
    val statusCode: Int,
    val timestamp: Long,
    val requestBody: String,
    val responseBody: String,
    val error: String?,
    val type: HttpLogEntryType = HttpLogEntryType.HTTP_REQUEST,
    val eventMessage: String = ""
)
