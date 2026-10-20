package top.steins.autologin.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class HttpLogStorageTest {
    private val logStore = HttpLogStorage()

    @Before
    fun setUp() {
        logStore.setEnabled(true)
    }

    @Test
    fun newStorage_startsDisabledAndEmpty() {
        val storage = HttpLogStorage()

        assertFalse(storage.isEnabled)
        storage.add(testEntry(1))
        assertEquals(emptyList<HttpLogEntry>(), storage.logs.value)
    }

    @Test
    fun separateStorage_doesNotShareEntriesOrEnablement() {
        val other = HttpLogStorage()
        other.setEnabled(true)
        logStore.add(testEntry(1))
        other.add(testEntry(2))

        logStore.setEnabled(false)

        assertEquals(listOf(testEntry(2)), other.logs.value)
        other.add(testEntry(3))
        assertEquals(listOf(testEntry(2), testEntry(3)), other.logs.value)
        assertEquals(emptyList<HttpLogEntry>(), logStore.logs.value)
    }

    @Test
    fun add_whenDisabled_doesNotRecordEntry() {
        logStore.setEnabled(false)

        logStore.add(testEntry(1))

        assertEquals(emptyList<HttpLogEntry>(), logStore.logs.value)
    }

    @Test
    fun logAccountInfoRefresh_recordsReasonAsRefreshEvent() {
        logStore.clear()
        try {
            logStore.logAccountInfoRefresh("用户点击刷新账号信息")

            val entry = logStore.logs.value.single()
            assertEquals(HttpLogEntryType.ACCOUNT_INFO_REFRESH, entry.type)
            assertEquals("用户点击刷新账号信息", entry.eventMessage)
            assertEquals("REFRESH", entry.method)
        } finally {
            logStore.clear()
        }
    }

    @Test
    fun add_keepsOnlyTheLatestTwoHundredEntries() {
        logStore.clear()
        try {
            repeat(250) { index ->
                logStore.add(testEntry(index.toLong()))
            }

            val logs = logStore.logs.value
            assertEquals(200, logs.size)
            assertEquals(50L, logs.first().id)
            assertEquals(249L, logs.last().id)
        } finally {
            logStore.clear()
        }
    }

    private fun testEntry(id: Long) = HttpLogEntry(
        id = id,
        method = "GET",
        url = "http://example.com/$id",
        statusCode = 200,
        timestamp = id,
        requestBody = "",
        responseBody = "",
        error = null
    )
}
