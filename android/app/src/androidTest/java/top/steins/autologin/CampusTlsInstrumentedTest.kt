package top.steins.autologin

import android.net.http.X509TrustManagerExtensions
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import top.steins.autologin.network.allowCampusCertificateErrors
import java.security.cert.CertificateException

@RunWith(AndroidJUnit4::class)
class CampusTlsInstrumentedTest {
    @Test
    fun androidTrustManager_onlyBypassesCampusCertificateChains() {
        val client = OkHttpClient.Builder().allowCampusCertificateErrors().build()
        val extensions = X509TrustManagerExtensions(requireNotNull(client.x509TrustManager))
        val certificate = HeldCertificate.Builder().rsa2048().commonName("Untrusted test CA").build()
        val chain = arrayOf(certificate.certificate)

        for (host in listOf("wlgn.bjut.edu.cn", "lgn.bjut.edu.cn", "jfself.bjut.edu.cn")) {
            extensions.checkServerTrusted(chain, "RSA", host)
        }
        assertThrows(CertificateException::class.java) {
            extensions.checkServerTrusted(chain, "RSA", "aloginupdate.steins.top")
        }
    }
}
