package com.nuvio.tv.core.mediaserver.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals
import com.nuvio.tv.core.mediaserver.assertFailsWith
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** The Ktor wiring under the client: real requests against a mock engine (runs on every platform, iOS included). */
class KtorMediaServerHttpTest {
    private fun http(
        maxBody: Long = KtorMediaServerHttp.MAX_BODY_BYTES,
        cert: (String) -> MediaServerException.CertificateUntrusted? = { null },
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = KtorMediaServerHttp(HttpClient(MockEngine(handler)), maxBody, cert)

    @Test
    fun getSendsTheMethodUrlAndHeadersAndReadsTheBody() = runBlocking<Unit> {
        var seen: io.ktor.client.request.HttpRequestData? = null
        val h = http { req ->
            seen = req
            respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Extra" to listOf("1")))
        }
        val r = h.execute(MediaServerRequest("GET", "http://nas:8096/System/Info/Public?x=1", mapOf("Authorization" to "MediaBrowser Client=\"T\"")))
        assertEquals(200, r.status)
        assertEquals("""{"ok":true}""", r.body)
        assertEquals("1", r.headers.entries.first { it.key.equals("X-Extra", true) }.value)
        val req = seen!!
        assertEquals("GET", req.method.value)
        assertEquals("http://nas:8096/System/Info/Public?x=1", req.url.toString())
        assertEquals("MediaBrowser Client=\"T\"", req.headers["Authorization"])
        assertEquals("application/json", req.headers["Accept"])
    }

    @Test
    fun aJsonBodyIsSentWithExactlyApplicationJsonAndNoCharsetSuffix() = runBlocking<Unit> {
        var contentType: ContentType? = null
        var sent: String? = null
        val h = http { req ->
            contentType = req.body.contentType
            sent = req.body.toByteArray().decodeToString()
            respond("", HttpStatusCode.NoContent)
        }
        h.execute(MediaServerRequest("POST", "http://nas:8096/Sessions/Playing", body = """{"ItemId":"x"}"""))
        assertEquals("Jellyfin answers 415 to 'application/json; charset=utf-8' on the session routes", "application/json", contentType.toString())
        assertEquals("""{"ItemId":"x"}""", sent)
    }

    @Test
    fun aNon2xxIsReturnedNotThrownAndRetryAfterIsParsed() = runBlocking<Unit> {
        val h = http { respond("busy", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "30")) }
        val r = h.execute(MediaServerRequest("GET", "http://nas:8096/x"))
        assertEquals(503, r.status)
        assertEquals(30L, r.retryAfterSeconds)
        assertTrue(!r.isSuccess)
        val none = http { respond("", HttpStatusCode.NotFound) }.execute(MediaServerRequest("GET", "http://nas:8096/x"))
        assertNull(none.retryAfterSeconds)
        val date = http { respond("", HttpStatusCode.ServiceUnavailable, headersOf("retry-after", "Wed, 21 Oct 2026 07:28:00 GMT")) }.execute(MediaServerRequest("GET", "http://nas:8096/x"))
        assertNull("HTTP-date forms are ignored, not mis-parsed", date.retryAfterSeconds)
    }

    @Test
    fun aTransportFailureIsUnreachableAndNeverALeakyEngineException() = runBlocking<Unit> {
        val h = http { throw RuntimeException("Connection refused: http://nas:8096/Users/AuthenticateByName?api_key=SECRET") }
        val e = assertFailsWith<MediaServerException.Unreachable> { h.execute(MediaServerRequest("GET", "http://nas:8096/x")) }
        assertTrue("the surfaced message carries no URL: ${e.message}", !e.message.orEmpty().contains("SECRET"))
        assertEquals(false, e.timedOut)
    }

    @Test
    fun aSlowServerIsATimeout() = runBlocking<Unit> {
        val h = http { delay(60_000); respond("late", HttpStatusCode.OK) }
        val e = assertFailsWith<MediaServerException.Unreachable> { h.execute(MediaServerRequest("GET", "http://nas:8096/x", timeoutMs = 50)) }
        assertTrue(e.timedOut)
    }

    @Test
    fun anOversizedResponseIsRefused() = runBlocking<Unit> {
        val declared = http(maxBody = 10) { respond("x".repeat(5000), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "5000")) }
        assertFailsWith<MediaServerException.Malformed> { declared.execute(MediaServerRequest("GET", "http://nas:8096/x")) }
        val actual = http(maxBody = 10) { respond("this body is longer than ten", HttpStatusCode.OK) }
        assertFailsWith<MediaServerException.Malformed> { actual.execute(MediaServerRequest("GET", "http://nas:8096/x")) }
    }

    @Test
    fun aHandshakeTheTrustPolicyRefusedSurfacesAsAnUntrustedCertificate() = runBlocking<Unit> {
        val untrusted = MediaServerException.CertificateUntrusted("nas:8920", "SELF_SIGNED", "AB12")
        val h = http(cert = { authority -> untrusted.takeIf { authority == "nas:8920" } }) { throw RuntimeException("handshake_failure") }
        val e = assertFailsWith<MediaServerException.CertificateUntrusted> { h.execute(MediaServerRequest("GET", "https://nas:8920/System/Info/Public")) }
        assertEquals("AB12", e.fingerprint)
        // another host's failure is just unreachable
        assertFailsWith<MediaServerException.Unreachable> { h.execute(MediaServerRequest("GET", "https://other:8920/x")) }
    }

    @Test
    fun theRoutingClientBuildsOneClientPerAuthorityAndRefusesNonHttpUrls() = runBlocking<Unit> {
        val created = mutableListOf<String>()
        val trust = com.nuvio.tv.core.mediaserver.client.MediaServerTrust(
            com.nuvio.tv.core.mediaserver.store.MediaServerTrustStore({ null }, { }, { }),
        )
        val routing = AuthorityRoutingHttp(trust) { authority, _ ->
            created += authority
            HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        }
        routing.execute(MediaServerRequest("GET", "http://nas:8096/a"))
        routing.execute(MediaServerRequest("GET", "http://nas:8096/b"))
        routing.execute(MediaServerRequest("GET", "https://nas:8920/c"))
        assertEquals(listOf("nas:8096", "nas:8920"), created)
        assertFailsWith<MediaServerException.Malformed> { routing.execute(MediaServerRequest("GET", "ftp://nas/x")) }
    }
}
