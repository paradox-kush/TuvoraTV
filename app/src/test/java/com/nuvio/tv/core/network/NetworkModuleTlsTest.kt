package com.nuvio.tv.core.network

import android.content.Context
import com.nuvio.tv.core.di.NetworkModule
import com.nuvio.tv.core.di.PanelHostGuardInterceptor
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Regression guard for `fix(network): validate TLS on first-party API traffic`.
 *
 * Before the fix, [NetworkModule.provideOkHttpClient] — the unnamed default binding that first-party
 * traffic (TMDB, Trakt, the updater, the self-hosted sync backend) inherits — installed a trust-all
 * X509TrustManager plus an always-true hostname verifier. The first assertion below fails on that
 * old wiring; the permissive lane is preserved for addon/IPTV hosts on a separate, cache-isolated
 * client.
 */
class NetworkModuleTlsTest {

    private fun context(): Context {
        val dir = Files.createTempDirectory("nm-tls-test").toFile()
        return mockk(relaxed = true) {
            every { cacheDir } returns dir
        }
    }

    @Test
    fun `default first-party client validates TLS, not trust-all`() {
        val default = NetworkModule.provideOkHttpClient(context())
        // OkHttp's stock verifier is used only when no override is installed; a trust-all client
        // instead carries an anonymous `{ _, _ -> true }` lambda whose simpleName is not this.
        assertEquals(
            "Default client must use OkHttp's validating hostname verifier, not a trust-all lambda",
            "OkHostnameVerifier",
            default.hostnameVerifier.javaClass.simpleName
        )
    }

    @Test
    fun `addon-permissive client is trust-all and cache-isolated from first-party`() {
        val ctx = context()
        val default = NetworkModule.provideOkHttpClient(ctx)
        val permissive = NetworkModule.provideAddonPermissiveOkHttpClient(ctx, default)

        assertNotEquals(
            "Permissive client must not share the validating verifier",
            "OkHostnameVerifier",
            permissive.hostnameVerifier.javaClass.simpleName
        )
        assertNotEquals(
            "Permissive client must install its own trust-all SSLSocketFactory",
            default.sslSocketFactory,
            permissive.sslSocketFactory
        )
        // Separate cache dirs: a response fetched without cert validation must never be reused for
        // a first-party request sharing the same cache key.
        assertEquals("http_cache", default.cache?.directory?.name)
        assertEquals("addon_http_cache", permissive.cache?.directory?.name)
    }

    @Test
    fun `permissive client inherits the Xtream panel-guard lane`() {
        val ctx = context()
        val permissive =
            NetworkModule.provideAddonPermissiveOkHttpClient(ctx, NetworkModule.provideOkHttpClient(ctx))
        assertTrue(
            "AddonApi/XtreamApi ride the permissive client, so it must inherit the panel guard",
            permissive.interceptors.any { it is PanelHostGuardInterceptor }
        )
    }
}
