package top.steins.autologin.network

import top.steins.autologin.domain.model.HttpLogEntry
import top.steins.autologin.domain.model.HttpLogEntryType
import top.steins.autologin.domain.port.HttpLogSink

fun HttpLogSink.logAccountInfoRefresh(reason: String) {
    if (!isEnabled) return
    add(
        HttpLogEntry(
            id = System.nanoTime(),
            method = "REFRESH",
            url = "app://account-info/refresh",
            statusCode = 0,
            timestamp = System.currentTimeMillis(),
            requestBody = "",
            responseBody = "",
            error = null,
            type = HttpLogEntryType.ACCOUNT_INFO_REFRESH,
            eventMessage = reason
        )
    )
}
