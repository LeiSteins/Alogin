package top.steins.autologin.network

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CancellableCallTest {
    private val request = Request.Builder().url("https://example.com/").build()

    @Test
    fun cancellationAfterResponseBeforeDispatch_closesUndeliveredBody() = runTest {
        val call = ControlledCall(request)
        val body = TrackingBody()
        var delivered = false
        val task = async {
            Call.Factory { call }.executeCancellable(request).use { delivered = true }
        }
        runCurrent()
        call.respond(response(body))
        task.cancel()
        runCurrent()

        assertFalse(delivered)
        assertTrue(body.closed)
        assertTrue(call.isCanceled())
    }

    @Test
    fun responseArrivingAfterCancellation_isClosed() = runTest {
        val call = ControlledCall(request)
        val body = TrackingBody()
        val task = async { Call.Factory { call }.executeCancellable(request) }
        runCurrent()
        task.cancel()
        runCurrent()
        call.respond(response(body))

        assertTrue(call.isCanceled())
        assertTrue(body.closed)
    }

    @Test
    fun deliveredResponse_staysOpenUntilCallerClosesIt() = runTest {
        val call = ControlledCall(request)
        val body = TrackingBody()
        val task = async { Call.Factory { call }.executeCancellable(request) }
        runCurrent()
        call.respond(response(body))
        val delivered = task.await()

        assertFalse(body.closed)
        delivered.close()
        assertTrue(body.closed)
        assertFalse(call.isCanceled())
    }

    private fun response(body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
}

private class TrackingBody : ResponseBody() {
    var closed = false
    private val buffer = Buffer().writeUtf8("response")
    override fun contentType(): MediaType? = null
    override fun contentLength() = 8L
    override fun source(): BufferedSource = buffer
    override fun close() {
        closed = true
        super.close()
    }
}

private class ControlledCall(private val original: Request) : Call by OkHttpClient().newCall(original) {
    private lateinit var callback: Callback
    private var executed = false
    private var cancelled = false
    override fun request() = original
    override fun execute(): Response = throw IOException("Use enqueue")
    override fun enqueue(responseCallback: Callback) {
        executed = true
        callback = responseCallback
    }
    override fun cancel() { cancelled = true }
    override fun isExecuted() = executed
    override fun isCanceled() = cancelled
    override fun timeout() = Timeout()
    override fun clone(): Call = ControlledCall(original)
    fun respond(response: Response) = callback.onResponse(this, response)
}
