package top.steins.autologin.network

import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates

private val campusCertificates by lazy {
    HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates()
        .addInsecureHost("wlgn.bjut.edu.cn")
        .addInsecureHost("lgn.bjut.edu.cn")
        .addInsecureHost("jfself.bjut.edu.cn")
        .build()
}

/**
 * 按兼容需求跳过这三个校园网主机的证书链校验，避免设备 CA 差异阻断请求。
 * 保留 HTTPS 和主机名校验；其他主机（包括重定向目标）仍校验证书链。
 * 注意：这些主机不再验证证书颁发者，无法抵御中间人攻击。
 */
internal fun OkHttpClient.Builder.allowCampusCertificateErrors(): OkHttpClient.Builder =
    sslSocketFactory(campusCertificates.sslSocketFactory(), campusCertificates.trustManager)
