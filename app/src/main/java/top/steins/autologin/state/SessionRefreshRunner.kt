package top.steins.autologin.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 只管理刷新任务的代次、延迟、重试和登录优先级，不接触主页或认证状态。 */
internal class SessionRefreshRunner(private val scope: CoroutineScope) {
    var generation = 0L
        private set
    private var refreshJob: Job? = null
    private var debounceJob: Job? = null

    fun debounce(delayMs: Long, operation: () -> Unit) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            operation()
        }
    }

    fun start(
        attempts: Int,
        initialDelayMs: Long,
        retryDelayMs: Long,
        prepare: suspend () -> Unit,
        onStart: () -> Unit,
        onFinish: (Long) -> Unit,
        attempt: suspend (generation: Long, attempt: Int) -> Boolean
    ) {
        generation += 1
        val expected = generation
        onStart()
        refreshJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                prepare()
                if (initialDelayMs > 0) delay(initialDelayMs)
                repeat(attempts) { index ->
                    if (attempt(expected, index)) return@launch
                    if (index < attempts - 1) delay(retryDelayMs)
                }
            } finally {
                onFinish(expected)
            }
        }
        // Main.immediate 可能在启动调用返回前调度下一次刷新；先登记任务，避免覆盖新任务引用。
        refreshJob = job
        job.start()
    }

    suspend fun cancelForLogin() {
        val pendingDebounce = debounceJob
        pendingDebounce?.cancelAndJoin()
        if (debounceJob === pendingDebounce) debounceJob = null
        val pendingRefresh = refreshJob
        pendingRefresh?.cancelAndJoin()
        if (refreshJob === pendingRefresh) refreshJob = null
        // 使取消前分配的代次失效，旧请求不得覆盖登录确认结果。
        generation += 1
    }
}
