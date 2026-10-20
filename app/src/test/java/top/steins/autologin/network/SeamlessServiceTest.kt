package top.steins.autologin.network

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import top.steins.autologin.R

class SeamlessServiceTest {
    private val oldMacs = listOf("001122334401", "001122334402", "001122334403")
    private val newMac = "AABBCC112233"
    private val page = """window.location.href = '/Self/service/unbindmac?mac=' + mac + "&ajaxCsrfToken=" + 'fresh-token';"""

    private fun list(macs: List<String>, total: Int = macs.size) =
        """{"total":$total,"rows":[${macs.joinToString(",") { """["1","$it","#Android","",""]""" }}]}"""

    private fun service(execute: suspend (Request) -> String, isCurrent: () -> Boolean = { true }) =
        SeamlessService(execute, isCurrent, { "msg:$it" }, "test-agent")

    @Test
    fun addingFourthDevice_postsNewMacWithoutPreemptivelyUnbindingAnyDevice() = runTest {
        val requests = mutableListOf<Request>()
        val responses = ArrayDeque(listOf(list(oldMacs), page, """{"state":"success"}""", list(oldMacs.take(2) + newMac)))
        val result = service({ requests += it; responses.removeFirst() })
            .perform(SeamlessAction.Bind("aa:bb:cc:11:22:33", allowReplacement = true))

        assertEquals(SeamlessActionResult.Success, result)
        assertEquals(listOf("/Self/service/getMacList", "/Self/service/myMac", "/Self/service/bindMac", "/Self/service/getMacList"), requests.map { it.url.encodedPath })
        val post = requests[2]
        assertEquals("POST", post.method)
        val form = post.body as FormBody
        assertEquals(newMac, form.value(0))
        assertEquals("0", form.value(1))
        assertEquals("fresh-token", form.value(2))
    }

    @Test
    fun countReachingThreeAfterEditorOpened_requiresReplacementConfirmationBeforeSubmitting() = runTest {
        val requests = mutableListOf<Request>()
        val result = service({ requests += it; list(oldMacs) }).perform(SeamlessAction.Bind(newMac))
        assertEquals(SeamlessActionResult.Failure("msg:${R.string.seamless_replacement_required}"), result)
        assertEquals(1, requests.size)
    }

    @Test
    fun duplicateBinding_isRejectedWithoutAnyWrite() = runTest {
        val requests = mutableListOf<Request>()
        val result = service({ requests += it; list(oldMacs) }).perform(SeamlessAction.Bind(oldMacs[0], true))
        assertEquals(SeamlessActionResult.Failure("msg:${R.string.seamless_duplicate_mac}"), result)
        assertEquals(1, requests.size)
    }

    @Test
    fun toggle_checksExpectedStateBeforeIssuingStateChangingGet() = runTest {
        val requests = mutableListOf<Request>()
        val result = service({ requests += it; "\"关闭\"" }).perform(SeamlessAction.Toggle(SeamlessStatus.Enabled))
        assertTrue(result is SeamlessActionResult.Failure)
        assertEquals(1, requests.size)
    }

    @Test
    fun toggleTimeout_isIndeterminateAndDoesNotRetry() = runTest {
        val requests = mutableListOf<Request>()
        val result = service({
            requests += it
            if (requests.size == 1) "\"开启\"" else throw IOException("timeout")
        }).perform(SeamlessAction.Toggle(SeamlessStatus.Enabled))
        assertTrue(result is SeamlessActionResult.Indeterminate)
        assertEquals(2, requests.size)
    }

    @Test
    fun successfulToggle_isConfirmedByNewStatusQuery() = runTest {
        val responses = ArrayDeque(listOf("\"开启\"", "<html>redirect</html>", "\"关闭\""))
        assertEquals(SeamlessActionResult.Success, service({ responses.removeFirst() }).perform(SeamlessAction.Toggle(SeamlessStatus.Enabled)))
        assertTrue(responses.isEmpty())
    }

    @Test
    fun unbind_usesFreshPageTokenAndConfirmsDeviceDisappeared() = runTest {
        val requests = mutableListOf<Request>()
        val responses = ArrayDeque(listOf(list(oldMacs), page, "<html>result</html>", list(oldMacs.drop(1))))
        val result = service({ requests += it; responses.removeFirst() }).perform(SeamlessAction.Unbind(oldMacs[0]))
        assertEquals(SeamlessActionResult.Success, result)
        assertEquals(oldMacs[0], requests[2].url.queryParameter("mac"))
        assertEquals("fresh-token", requests[2].url.queryParameter("ajaxCsrfToken"))
    }

    @Test
    fun networkChangeDuringPreflight_preventsWrite() = runTest {
        var requests = 0
        val result = service({ requests++; "\"开启\"" }, isCurrent = { false })
            .perform(SeamlessAction.Toggle(SeamlessStatus.Enabled))
        assertTrue(result is SeamlessActionResult.Failure)
        assertEquals(1, requests)
    }

    @Test
    fun devicePagination_keepsOfflineBindingsAndRejectsEmptyOrInconsistentResponses() = runTest {
        val pageNumbers = mutableListOf<String?>()
        val responses = ArrayDeque(listOf(list(oldMacs.take(2), 3), list(oldMacs.drop(2), 3)))
        val devices = service({ pageNumbers += it.url.queryParameter("pageNumber"); responses.removeFirst() }).readDevices()
        assertEquals(3, devices.total)
        assertEquals(listOf("1", "2"), pageNumbers)
        for (body in listOf("", """{"total":1,"rows":[]}""")) {
            try {
                service({ body }).readDevices()
                fail("Invalid response must not become an empty list")
            } catch (_: SeamlessProtocolException) { }
        }
    }
}
