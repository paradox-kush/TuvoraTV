package com.nuvio.tv.core.mediaserver.client

import com.nuvio.tv.core.mediaserver.policy.CertTrustPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.internal.tls.OkHostnameVerifier
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * One OkHttp client per server authority. System validation runs first; ONLY when it fails does the shared
 * [CertTrustPolicy] decide (via [MediaServerTrust.check]) whether a certificate this device pinned - or the
 * user is about to be asked to pin - applies. A publicly trusted chain is never pinned. Redirects are off
 * (a base path redirect is the user's to type, design 5.4); retries are the caller's.
 */
/** One HTTP client per server authority (`host:port`): its TLS glue knows exactly which host it is validating. */
internal fun createMediaServerHttpClient(authority: String, trust: MediaServerTrust): HttpClient {
    val system = systemTrustManager()
    val tofu = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val allowed = trust.check(authority, false, classify(e, chain), fingerprint(chain[0]))
                if (!allowed) throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
    val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tofu), null) }
    // A pinned self-signed certificate often carries no SAN for the LAN address it is reached by: hostname
    // verification is waived ONLY for the exact certificate the user pinned.
    val hostnameVerifier = HostnameVerifier { host, session ->
        if (OkHostnameVerifier.verify(host, session)) return@HostnameVerifier true
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@HostnameVerifier false
        trust.check(authority, false, CertTrustPolicy.FailureKind.HOSTNAME_MISMATCH, fingerprint(leaf))
    }
    return HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                sslSocketFactory(sslContext.socketFactory, tofu)
                hostnameVerifier(hostnameVerifier)
                connectTimeout(java.time.Duration.ofSeconds(10))
            }
        }
    }
}

private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

private fun fingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }

private fun classify(error: CertificateException, chain: Array<X509Certificate>): CertTrustPolicy.FailureKind {
    var cause: Throwable? = error
    while (cause != null) {
        if (cause is CertificateExpiredException) return CertTrustPolicy.FailureKind.EXPIRED
        cause = cause.cause
    }
    val leaf = chain.firstOrNull() ?: return CertTrustPolicy.FailureKind.OTHER
    return if (chain.size == 1 && leaf.subjectX500Principal == leaf.issuerX500Principal) {
        CertTrustPolicy.FailureKind.SELF_SIGNED
    } else {
        CertTrustPolicy.FailureKind.UNTRUSTED_CHAIN
    }
}
