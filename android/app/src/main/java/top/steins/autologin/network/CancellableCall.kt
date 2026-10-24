package top.steins.autologin.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** 取消协程时同步取消底层 OkHttp Call，避免过期刷新长期占用会话互斥锁。 */
internal suspend fun Call.Factory.executeCancellable(request: Request): Response =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(e))
                }
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                // 结果已提交但调用方尚未恢复时仍可能被取消，必须关闭未交付的响应。
                continuation.resume(response) { _, undelivered, _ -> undelivered.close() }
            }
        })
    }
