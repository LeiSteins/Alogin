package top.steins.autologin.network

import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class DnsRetryTest {

    @Test
    fun retryAfterDnsFailure_retriesUnknownHostUntilRequestSucceeds() = runTest {
        var attempts = 0

        val result = retryAfterDnsFailure {
            attempts += 1
            if (attempts < DNS_LOOKUP_ATTEMPTS) throw UnknownHostException("lgn.bjut.edu.cn")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(DNS_LOOKUP_ATTEMPTS, attempts)
    }

    @Test
    fun retryAfterDnsFailure_doesNotRetryOtherIoFailures() = runTest {
        var attempts = 0

        try {
            retryAfterDnsFailure<String> {
                attempts += 1
                throw IOException("connection refused")
            }
            fail("Expected IOException")
        } catch (_: IOException) {
            assertEquals(1, attempts)
        }
    }
}
