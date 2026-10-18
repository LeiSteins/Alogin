package top.steins.autologin.network

internal sealed interface LoginParseResult {
    data object Success : LoginParseResult
    data object AlreadyOnline : LoginParseResult
    data class Failure(val serverMessage: String?) : LoginParseResult
    data object Unknown : LoginParseResult
}

/** 兼容 Dr.COM JSONP 与部分门户直接返回的 JSON/文本响应。 */
internal object LoginResponseParser {
    private val resultPattern = Regex(
        """[\"']?result[\"']?\s*:\s*[\"']?(\d+)[\"']?""",
        RegexOption.IGNORE_CASE
    )
    private val messagePattern = Regex(
        """[\"']?(?:msga|msg|message)[\"']?\s*:\s*[\"']([^\"']+)[\"']""",
        RegexOption.IGNORE_CASE
    )
    private val alreadyOnlineIpPattern = Regex(
        """(?<![a-z])IP\s*[:：]?\s*(?:(?:\d{1,3}\.){3}\d{1,3}\s*)?(?:已经|已)\s*在线""",
        RegexOption.IGNORE_CASE
    )

    fun parse(responseText: String): LoginParseResult {
        val normalized = responseText.replace("\\\"", "\"")
        val serverMessage = extractServerMessage(normalized)
        val isAlreadyOnline = alreadyOnlineIpPattern.containsMatchIn(serverMessage ?: normalized)
        return when (resultPattern.find(normalized)?.groupValues?.getOrNull(1)) {
            "1" -> LoginParseResult.Success
            "0" -> if (isAlreadyOnline) {
                LoginParseResult.AlreadyOnline
            } else {
                LoginParseResult.Failure(serverMessage)
            }
            else -> when {
                isAlreadyOnline -> LoginParseResult.AlreadyOnline

                normalized.contains("认证成功") || normalized.contains("登录成功") ->
                    LoginParseResult.Success

                normalized.contains("认证失败") || normalized.contains("登录失败") -> {
                    LoginParseResult.Failure(serverMessage)
                }

                else -> LoginParseResult.Unknown
            }
        }
    }

    private fun extractServerMessage(response: String): String? =
        messagePattern.find(response)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
}
