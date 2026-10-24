package top.steins.autologin.network

import android.net.Network
import java.net.Proxy
import okhttp3.Dns
import okhttp3.OkHttpClient

/** 将 OkHttp 的连接、DNS 和代理选择全部固定到同一个物理网络。 */
internal fun OkHttpClient.Builder.bindToCampusNetwork(network: Network): OkHttpClient.Builder =
    socketFactory(network.socketFactory)
        .dns(Dns { hostname -> network.getAllByName(hostname).toList() })
        .proxy(Proxy.NO_PROXY)

/**
 * 每个物理 Network 复用一个客户端；网络变化后立即废弃旧连接，避免连接池跨网络复用。
 */
internal class CampusHttpClientCache(
    private val buildClient: (Network) -> OkHttpClient
) : AutoCloseable {

    private val lock = Any()

    @Volatile
    private var cached: CachedClient? = null

    fun get(network: Network): OkHttpClient {
        cached?.takeIf { it.network == network }?.let { return it.client }
        return synchronized(lock) {
            cached?.takeIf { it.network == network }?.client ?: run {
                cached?.client?.closeConnections()
                buildClient(network).also { client ->
                    cached = CachedClient(network, client)
                }
            }
        }
    }

    fun evict(network: Network? = null) {
        synchronized(lock) {
            val current = cached ?: return
            if (network == null || current.network == network) {
                cached = null
                current.client.closeConnections()
            }
        }
    }

    override fun close() = evict()

    private fun OkHttpClient.closeConnections() {
        dispatcher.cancelAll()
        connectionPool.evictAll()
    }

    private data class CachedClient(
        val network: Network,
        val client: OkHttpClient
    )
}
