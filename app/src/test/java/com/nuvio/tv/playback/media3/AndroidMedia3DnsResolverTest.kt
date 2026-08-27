package com.nuvio.tv.playback.media3

import com.nuvio.tv.playback.core.ApplicationDnsKey
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.ProxyMode
import com.nuvio.tv.playback.core.TlsPolicy
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy
import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AndroidMedia3DnsResolverTest {

    @Test
    fun `successive provider requests resolve their own DNS objects`() {
        val dnsA = NamedDns
        val dnsB = OtherNamedDns
        val resolver = ApplicationDnsResolver { key ->
            when (key.value) {
                "provider-a" -> dnsA
                "provider-b" -> dnsB
                else -> null
            }
        }

        val first = resolveApplicationDns(plan(ApplicationDnsKey("provider-a")), resolver).success()
        val zapped = resolveApplicationDns(plan(ApplicationDnsKey("provider-b")), resolver).success()

        assertSame(dnsA, first)
        assertSame(dnsB, zapped)
    }

    @Test
    fun `system DNS resolves no application object and missing or unknown key fails typed`() {
        var resolverCalls = 0
        val resolver = ApplicationDnsResolver {
            resolverCalls++
            null
        }

        val system = resolveApplicationDns(plan(null, DnsPolicy.SYSTEM), resolver).success()
        val missing = resolveApplicationDns(plan(null), resolver) as PlaybackResult.Failure
        val unknown = resolveApplicationDns(plan(ApplicationDnsKey("unknown")), resolver) as PlaybackResult.Failure

        assertNull(system)
        assertEquals(1, resolverCalls)
        assertEquals(FailureCode.NETWORK_UNREACHABLE, missing.failure.code)
        assertEquals(FailureCode.NETWORK_UNREACHABLE, unknown.failure.code)
    }

    private fun plan(
        key: ApplicationDnsKey?,
        policy: DnsPolicy = DnsPolicy.SHARED_APPLICATION_RESOLVER,
    ) = Media3NetworkPlan(
        url = "https://media.invalid/live.ts",
        headers = emptyMap(),
        mimeType = null,
        confirmedRawTransportStream = false,
        followRedirects = true,
        preserveAuthorizationAcrossHosts = false,
        dnsPolicy = policy,
        applicationDnsKey = key,
        tlsPolicy = TlsPolicy.STRICT,
        proxyMode = ProxyMode.SYSTEM,
        httpProxy = null,
        connectTimeoutMs = 15_000,
        readTimeoutMs = 60_000,
        callTimeoutMs = null,
        retryConnectionFailures = false,
        transientLoadRetryPolicy = TransientLoadRetryPolicy.SESSION_ONLY,
        drm = null,
    )

    private fun PlaybackResult<Dns?>.success(): Dns? = (this as PlaybackResult.Success).value

    private object NamedDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> = emptyList()
    }

    private object OtherNamedDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> = emptyList()
    }
}
