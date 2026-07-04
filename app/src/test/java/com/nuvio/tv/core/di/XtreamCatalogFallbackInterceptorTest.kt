package com.nuvio.tv.core.di

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression test for the production crash "IllegalStateException: cannot make
 * a new request because the previous response is still open" — thrown on the
 * OkHttp dispatcher thread (fatal) whenever a panel answered a catalog request
 * with 4xx/5xx and the interceptor proceeded to the cache lookup without
 * closing the error response first.
 */
class XtreamCatalogFallbackInterceptorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Same layering as NetworkModule: cache + fresh-window rewrite network
        // interceptor + the fallback app interceptor under test.
        client = OkHttpClient.Builder()
            .cache(Cache(tmp.newFolder(), 10L * 1024 * 1024))
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.isSuccessful && NetworkModule.isXtreamCatalogUrl(chain.request().url)) {
                    response.newBuilder()
                        .removeHeader("Pragma")
                        .header("Cache-Control", "public, max-age=3600")
                        .build()
                } else {
                    response
                }
            }
            .addInterceptor(XtreamCatalogFallbackInterceptor())
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun catalogRequest() = Request.Builder()
        .url(server.url("/player_api.php?username=u&password=p&action=get_live_streams&category_id=7"))
        .build()

    @Test
    fun `server error with empty cache returns the error instead of crashing`() {
        server.enqueue(MockResponse.Builder().code(500).body("panel error").build())

        client.newCall(catalogRequest()).execute().use { response ->
            assertEquals(500, response.code)
            assertEquals("panel error", response.body?.string())
        }
    }

    @Test
    fun `server error after a cached success serves the stale cached copy`() {
        server.enqueue(MockResponse.Builder().code(200).body("""[{"id":1}]""").build())
        server.enqueue(MockResponse.Builder().code(403).body("blocked").build())

        client.newCall(catalogRequest()).execute().use { response ->
            assertEquals(200, response.code)
            response.body?.string()
        }
        // Force revalidation so the second call actually hits the network 403.
        val noCacheRequest = catalogRequest().newBuilder()
            .cacheControl(okhttp3.CacheControl.Builder().noCache().build())
            .build()
        client.newCall(noCacheRequest).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("""[{"id":1}]""", response.body?.string())
        }
    }
}
