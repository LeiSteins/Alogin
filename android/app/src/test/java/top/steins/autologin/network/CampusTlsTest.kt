package top.steins.autologin.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class CampusTlsTest {
    @Test
    fun campusHosts_acceptSelfSignedCertificates() {
        for (host in listOf("wlgn.bjut.edu.cn", "lgn.bjut.edu.cn", "jfself.bjut.edu.cn")) {
            withTlsServer(certificateFor(host)) { port ->
                request(host, port).use { response ->
                    assertEquals("OK", response.body.string())
                }
            }
        }
    }

    @Test
    fun campusHost_acceptsExpiredCertificate() {
        val certificate = HeldCertificate.Builder()
            .commonName("lgn.bjut.edu.cn")
            .addSubjectAlternativeName("lgn.bjut.edu.cn")
            .validityInterval(0L, 1_000L)
            .build()
        withTlsServer(certificate) { port ->
            request("lgn.bjut.edu.cn", port).use { response ->
                assertEquals(200, response.code)
            }
        }
    }

    @Test
    fun otherHosts_rejectSelfSignedCertificates() {
        for (host in listOf("aloginupdate.steins.top", "other.bjut.edu.cn", "lgn.bjut.edu.cn.example.com")) {
            withTlsServer(certificateFor(host)) { port ->
                assertThrows(SSLHandshakeException::class.java) {
                    request(host, port).close()
                }
            }
        }
    }

    @Test
    fun campusHost_rejectsCertificateForDifferentHostname() {
        withTlsServer(certificateFor("other.example.com")) { port ->
            assertThrows(SSLPeerUnverifiedException::class.java) {
                request("lgn.bjut.edu.cn", port).close()
            }
        }
    }

    @Test
    fun redirectToOtherHost_stillRejectsSelfSignedCertificate() {
        withTlsServer(certificateFor("other.example.com")) { targetPort ->
            withTlsServer(
                certificateFor("lgn.bjut.edu.cn"),
                "HTTP/1.1 302 Found\r\nLocation: https://other.example.com:$targetPort/\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
            ) { port ->
                assertThrows(SSLHandshakeException::class.java) {
                    request("lgn.bjut.edu.cn", port).close()
                }
            }
        }
    }

    private fun certificateFor(host: String): HeldCertificate = HeldCertificate.Builder()
        .commonName(host)
        .addSubjectAlternativeName(host)
        .build()

    private fun request(host: String, port: Int): okhttp3.Response {
        val client = OkHttpClient.Builder()
            .allowCampusCertificateErrors()
            .dns { hostname ->
                listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
            }
            .proxy(java.net.Proxy.NO_PROXY)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        return try {
            client.newCall(Request.Builder().url("https://$host:$port/").build()).execute()
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun withTlsServer(
        certificate: HeldCertificate,
        response: String = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK",
        block: (Int) -> Unit
    ) {
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val factory = certificates.sslContext().serverSocketFactory
        (factory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).use { server ->
            server.soTimeout = 5_000
            val worker = thread(isDaemon = true) {
                try {
                    (server.accept() as SSLSocket).use { socket ->
                        socket.soTimeout = 5_000
                        socket.startHandshake()
                        val reader = socket.inputStream.bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: return@thread
                            if (line.isEmpty()) break
                        }
                        socket.outputStream.write(response.toByteArray(Charsets.US_ASCII))
                        socket.outputStream.flush()
                    }
                } catch (_: IOException) {
                    // 拒绝证书或主机名的测试会在握手/读取请求时关闭连接。
                }
            }
            try {
                block(server.localPort)
            } finally {
                server.close()
                worker.join(6_000)
            }
        }
    }
}
