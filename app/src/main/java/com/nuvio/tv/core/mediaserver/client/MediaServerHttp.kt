package com.nuvio.tv.core.mediaserver.client

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal data class MediaServerRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** A JSON body; sent as exactly `application/json` (Jellyfin answers 415 to a `; charset=utf-8` suffix on the session routes). */
    val body: String? = null,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /**
         * A server whose library is filled on demand fetches and probes the file while
         * the request waits: preparing the stream (up to ~30 s on the server side) plus the probe. Reference: Plezy waits 120 s for
         * a server's answer; only best-effort shelves get short deadlines.
         */
        const val SLOW_RESOLVE_TIMEOUT_MS = 90_000L
    }
}

internal data class MediaServerResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    val isSuccess: Boolean get() = status in 200..299

    /** `Retry-After` in seconds (a 503 while the server starts/stops carries it), or null. HTTP-date forms are ignored. */
    val retryAfterSeconds: Long?
        get() = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.trim()?.toLongOrNull()
}

/** What can go wrong talking to a server - the callers branch on the kind, never on a message. */
internal sealed class MediaServerException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No answer: DNS, refused, timeout, reset, a TLS failure the trust policy did not accept. */
    class Unreachable(message: String, cause: Throwable? = null, val timedOut: Boolean = false) : MediaServerException(message, cause)

    /** The server answered with a non-2xx status. */
    class Http(val status: Int, val retryAfterSeconds: Long? = null) : MediaServerException("HTTP $status") {
        /** The credentials were refused: a revoked/expired token, a wrong password, a disabled Quick Connect. */
        val isUnauthorized: Boolean get() = status == 401 || status == 403
        val isNotFound: Boolean get() = status == 404
        val isServiceUnavailable: Boolean get() = status == 503
    }

    /** 2xx but not what a media server answers (wrong JSON shape, an HTML page): not a Jellyfin/Emby address, or a proxy in the way. */
    class Malformed(message: String, cause: Throwable? = null) : MediaServerException(message, cause)

    /** The system certificate check failed and the user has not (yet) trusted this certificate: surface "trust this certificate?". */
    class CertificateUntrusted(val authority: String, val failure: String, val fingerprint: String) :
        MediaServerException("certificate for $authority not trusted ($failure)")
}

/** The one HTTP seam of the client: tests fake it, production is [KtorMediaServerHttp]. */
internal interface MediaServerHttp {
    suspend fun execute(request: MediaServerRequest): MediaServerResponse
}

/**
 * Ktor implementation. Redirects are not followed blindly by every engine, so the factory sets them off and
 * the caller treats a 3xx as the reverse-proxy / base-path problem it is (design 5.4: clients do not follow
 * `BaseUrl` redirects - the user types the base path). The whole request is bounded by [MediaServerRequest.timeoutMs]
 * with a coroutine timeout (no engine-specific timeout plugin needed), and every transport failure becomes
 * [MediaServerException.Unreachable] - never a raw engine exception, which can embed the URL.
 */
internal class KtorMediaServerHttp(
    private val client: HttpClient,
    private val maxBodyBytes: Long = MAX_BODY_BYTES,
    /** The certificate failure the platform trust glue recorded for an authority while the request was failing, if any. */
    private val certificateFailureFor: (authority: String) -> MediaServerException.CertificateUntrusted? = { null },
) : MediaServerHttp {
    override suspend fun execute(request: MediaServerRequest): MediaServerResponse {
        val authority = com.nuvio.tv.core.mediaserver.policy.ServerUrlPolicy.hostAuthority(request.url)
        try {
            return withTimeout(request.timeoutMs) {
                val response: HttpResponse = client.request(request.url) {
                    method = HttpMethod.parse(request.method)
                    header("Accept", "application/json")
                    request.headers.forEach { (name, value) -> header(name, value) }
                    request.body?.let { setBody(TextContent(it, ContentType.Application.Json)) }
                }
                val length = response.headers["Content-Length"]?.toLongOrNull()
                if (length != null && length > maxBodyBytes) throw MediaServerException.Malformed("response too large")
                val text = response.bodyAsText()
                if (text.length > maxBodyBytes) throw MediaServerException.Malformed("response too large")
                MediaServerResponse(
                    status = response.status.value,
                    headers = response.headers.entries().associate { (name, values) -> name to values.joinToString(",") },
                    body = text,
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw MediaServerException.Unreachable("the server did not answer in time", e, timedOut = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException) {
            throw e
        } catch (e: Throwable) {
            authority?.let(certificateFailureFor)?.let { throw it }
            throw MediaServerException.Unreachable("could not reach the server", e)
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 16L * 1024 * 1024
    }
}

/**
 * Production [MediaServerHttp]: one Ktor client per server authority (`host:port`), created on first use, so
 * each client's TLS glue knows which host it validates ([MediaServerTrust]) and a certificate failure at one
 * server can never affect another. A failed request whose handshake the trust policy refused surfaces as
 * [MediaServerException.CertificateUntrusted] (the caller offers trust-on-first-use).
 */
internal class AuthorityRoutingHttp(
    private val trust: MediaServerTrust,
    private val clientFactory: (authority: String, trust: MediaServerTrust) -> HttpClient = ::createMediaServerHttpClient,
) : MediaServerHttp {
    private val lock = Any()
    private val clients = mutableMapOf<String, KtorMediaServerHttp>()

    override suspend fun execute(request: MediaServerRequest): MediaServerResponse {
        val authority = com.nuvio.tv.core.mediaserver.policy.ServerUrlPolicy.hostAuthority(request.url)
            ?: throw MediaServerException.Malformed("not an http(s) address")
        val http = synchronized(lock) {
            clients.getOrPut(authority) {
                KtorMediaServerHttp(clientFactory(authority, trust), certificateFailureFor = { trust.asException(it) })
            }
        }
        return http.execute(request)
    }
}
