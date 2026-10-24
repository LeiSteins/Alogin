package top.steins.autologin.network

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SelfServiceSessionFailure { MISSING_ACCOUNT, NETWORK_UNAVAILABLE, EXPIRED }

internal class SelfServiceSessionException(val reason: SelfServiceSessionFailure) : Exception()

internal data class SelfServiceSessionHandle<Route>(
    val route: Route,
    val account: String,
    val generation: Long
)

/**
 * 统一拥有自助服务的认证身份、Cookie 生命周期与请求串行化。
 * Route 由平台适配层提供；建立会话不依赖任何功能页面的数据加载。
 */
internal class SelfServiceSession<Route>(
    private val awaitRoute: suspend () -> Route?,
    private val isCurrent: (Route) -> Boolean,
    private val openSession: suspend (String, Route) -> Unit,
    private val clearCookies: () -> Unit,
    private val closeResources: () -> Unit
) : AutoCloseable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var current: SelfServiceSessionHandle<Route>? = null
    private var generation = 0L

    suspend fun <T> withSession(
        username: String,
        forceRenew: Boolean = false,
        operation: suspend (SelfServiceSessionHandle<Route>) -> T
    ): T = mutex.withLock {
        ensureOpen()
        val account = username.substringBefore("@").trim()
        if (account.isBlank()) throw SelfServiceSessionException(SelfServiceSessionFailure.MISSING_ACCOUNT)
        val route = awaitRoute() ?: run {
            invalidate()
            throw SelfServiceSessionException(SelfServiceSessionFailure.NETWORK_UNAVAILABLE)
        }
        ensureOpen()
        var handle = current
        if (forceRenew || handle == null || handle.account != account || handle.route != route || !isCurrent(route)) {
            invalidate()
            try {
                if (!isCurrent(route)) throw SelfServiceSessionException(SelfServiceSessionFailure.EXPIRED)
                openSession(account, route)
                ensureOpen()
                if (!isCurrent(route)) throw SelfServiceSessionException(SelfServiceSessionFailure.EXPIRED)
                handle = SelfServiceSessionHandle(route, account, generation)
                current = handle
            } catch (error: Throwable) {
                invalidate()
                throw error
            }
        }
        execute(handle, validateResult = true, operation)
    }

    /** 写操作只接受已经建立且仍有效的会话，绝不自动重建后重发。 */
    suspend fun <T> withExistingSession(
        username: String? = null,
        expectedGeneration: Long? = null,
        operation: suspend (SelfServiceSessionHandle<Route>) -> T
    ): T = mutex.withLock {
        ensureOpen()
        val handle = current ?: throw SelfServiceSessionException(SelfServiceSessionFailure.EXPIRED)
        requireCurrent(handle)
        if ((username != null && username.substringBefore("@").trim() != handle.account) ||
            (expectedGeneration != null && expectedGeneration != handle.generation)
        ) throw SelfServiceSessionException(SelfServiceSessionFailure.EXPIRED)
        execute(handle, validateResult = false, operation)
    }

    suspend fun clear() = mutex.withLock { invalidate() }

    private suspend fun <T> execute(
        handle: SelfServiceSessionHandle<Route>,
        validateResult: Boolean,
        operation: suspend (SelfServiceSessionHandle<Route>) -> T
    ): T {
        requireCurrent(handle)
        try {
            val result = operation(handle)
            ensureOpen()
            // 写请求提交后可能主动断开当前设备，必须保留服务层的 Success/Indeterminate 结果。
            if (validateResult) requireCurrent(handle)
            else if (!isCurrent(handle.route)) invalidate()
            return result
        } catch (error: SelfServiceSessionException) {
            invalidate()
            throw error
        }
    }

    private fun requireCurrent(handle: SelfServiceSessionHandle<Route>) {
        ensureOpen()
        if (current != handle || !isCurrent(handle.route)) {
            invalidate()
            throw SelfServiceSessionException(SelfServiceSessionFailure.EXPIRED)
        }
    }

    private fun ensureOpen() {
        if (closed.get()) throw SelfServiceSessionException(SelfServiceSessionFailure.EXPIRED)
    }

    private fun invalidate() {
        current = null
        generation += 1
        clearCookies()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // 正在执行的请求会在返回前检查 closed；清理资源同时取消底层 HTTP 请求。
            clearCookies()
            closeResources()
        }
    }
}
