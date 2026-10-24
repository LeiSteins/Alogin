package top.steins.autologin.network

import java.net.UnknownHostException
import kotlinx.coroutines.delay

/** 仅对尚未发出 HTTP 请求的 DNS 解析失败做有限退避重试。 */
internal suspend fun <T> retryAfterDnsFailure(block: suspend () -> T): T {
    repeat(DNS_LOOKUP_ATTEMPTS) { attempt ->
        try {
            return block()
        } catch (error: UnknownHostException) {
            if (attempt == DNS_LOOKUP_ATTEMPTS - 1) throw error
            delay(DNS_RETRY_DELAYS_MS[attempt])
        }
    }
    error("DNS retry loop completed without a result")
}

internal const val DNS_LOOKUP_ATTEMPTS = 3
private val DNS_RETRY_DELAYS_MS = longArrayOf(500L, 1_000L)
