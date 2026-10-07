package com.nuvio.tv.core.mediaserver.client

import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserAuthApi
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserClient
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserClientIdentity
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserDialect
import com.nuvio.tv.core.mediaserver.policy.QuickConnectPolicy
import com.nuvio.tv.core.mediaserver.policy.ServerUrlPolicy
import com.nuvio.tv.core.mediaserver.store.MediaServerCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface DiscoveryResult {
    /** [baseUrl] answered as a media server. [typeMismatch] is true when the user picked a product the server is not. */
    data class Found(val baseUrl: String, val info: ServerInfo, val type: MediaServerType, val typeMismatch: Boolean) : DiscoveryResult

    data class Rejected(val reason: ServerUrlPolicy.Reason) : DiscoveryResult

    /** The system did not trust the server's certificate: offer [CertificateUntrusted] (trust it once, then retry). */
    data class CertificateNeedsTrust(val failure: MediaServerException.CertificateUntrusted) : DiscoveryResult

    /** Something answered 2xx but is not a Jellyfin/Emby server (a proxy login page, another app). */
    data object NotAMediaServer : DiscoveryResult

    data object Unreachable : DiscoveryResult
}

internal enum class HealthStatus { ONLINE, AUTH_ERROR, OFFLINE }

internal sealed interface QuickConnectOutcome {
    data class SignedIn(val session: AuthSession) : QuickConnectOutcome
    /** The server's request expired before anyone approved it: the caller regenerates a code. */
    data object Expired : QuickConnectOutcome
    data object Failed : QuickConnectOutcome
}

/**
 * Everything the screens and the source registrations need from the client layer, behind one seam: server
 * discovery/validation, health, sign-in (Quick Connect, password), approving another device's Quick Connect,
 * and an authenticated client per signed-in entry. Platform pieces (HTTP, secure storage, device identity)
 * are injected, so all of it is testable with fakes.
 */
internal class MediaServerServices(
    val http: MediaServerHttp,
    val credentials: MediaServerCredentialStore,
    private val identityProvider: () -> MediaBrowserClientIdentity,
    private val nowMs: () -> Long,
    private val staggerMs: Long = DISCOVERY_STAGGER_MS,
    /** Test seam: replaces the HTTP-backed client for a signed-in entry. Never set in production. */
    private val clientFactory: ((MediaServerEntry) -> MediaServerClient)? = null,
) {
    private val expired = MutableStateFlow<Set<String>>(emptySet())

    /** Server keys whose token the server refused (revoked / expired): the entry stays, the device must sign in again. */
    val expiredSessions: StateFlow<Set<String>> = expired.asStateFlow()

    private val credentialsVersion = MutableStateFlow(0L)

    /** Bumped whenever a sign-in is saved or removed, so observers of "who is signed in" (search signature, settings) re-read. */
    val credentialVersion: StateFlow<Long> = credentialsVersion.asStateFlow()

    fun notifyCredentialsChanged() = credentialsVersion.update { it + 1 }

    fun identity(): MediaBrowserClientIdentity = identityProvider()

    fun isSignedIn(entry: MediaServerEntry): Boolean = credentials.token(entry.serverKey) != null

    fun authApi(baseUrl: String, type: MediaServerType): MediaBrowserAuthApi =
        MediaBrowserAuthApi(http, baseUrl, MediaBrowserDialect.of(type), identity())

    /** The authenticated client of a SIGNED-IN entry that has an address on this device; null otherwise. */
    fun clientFor(entry: MediaServerEntry): MediaServerClient? {
        val address = entry.address?.takeIf { it.isNotBlank() } ?: return null
        if (!isSignedIn(entry)) return null
        clientFactory?.let { return it(entry) }
        return MediaBrowserClient(
            http = http,
            baseUrl = address,
            dialect = MediaBrowserDialect.of(entry.type),
            identity = identity(),
            userId = entry.userId,
            token = { credentials.token(entry.serverKey) },
            nowMs = nowMs,
        )
    }

    /** A server answered 401/403: drop its token (sign in again) and remember why. */
    fun onUnauthorized(serverKey: String) {
        credentials.remove(serverKey)
        expired.update { it + serverKey }
        notifyCredentialsChanged()
    }

    fun clearExpired(serverKey: String) = expired.update { it - serverKey }

    /**
     * Probes what the user typed: the candidates [ServerUrlPolicy] derives race (staggered, so the likeliest
     * answers first without waiting for a dead one to time out), the first media server to answer wins.
     */
    suspend fun discover(input: String, preferred: MediaServerType?): DiscoveryResult {
        val candidates = when (val outcome = ServerUrlPolicy.normalize(input)) {
            is ServerUrlPolicy.Outcome.Rejected -> return DiscoveryResult.Rejected(outcome.reason)
            is ServerUrlPolicy.Outcome.Ok -> outcome.candidates
        }
        val guess = preferred ?: MediaServerType.JELLYFIN
        val failures = mutableListOf<MediaServerException>()
        var found: DiscoveryResult.Found? = null
        coroutineScope {
            val results = Channel<Result<Pair<String, ServerInfo>>>(candidates.size)
            candidates.forEachIndexed { index, url ->
                launch {
                    delay(index * staggerMs)
                    results.send(
                        try {
                            Result.success(url to authApi(url, guess).publicInfo(PROBE_TIMEOUT_MS))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: MediaServerException) {
                            Result.failure(e)
                        },
                    )
                }
            }
            repeat(candidates.size) {
                val result = results.receive()
                result.onSuccess { (url, info) ->
                    val detected = info.detectedType
                    found = DiscoveryResult.Found(url, info, detected ?: guess, typeMismatch = preferred != null && detected != null && detected != preferred)
                }.onFailure { failures += it as MediaServerException }
                if (found != null) {
                    coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
                    return@coroutineScope
                }
            }
        }
        found?.let { return it }
        failures.filterIsInstance<MediaServerException.CertificateUntrusted>().firstOrNull()?.let { return DiscoveryResult.CertificateNeedsTrust(it) }
        // Only a 2xx that is not a media server counts as "not a media server"; a 404/refused/timeout is unreachable.
        if (failures.any { it is MediaServerException.Malformed }) return DiscoveryResult.NotAMediaServer
        return DiscoveryResult.Unreachable
    }

    /** Reachable AND token-valid: probes the current-user route (an authenticated call - streams and images are anonymous). */
    suspend fun health(entry: MediaServerEntry): HealthStatus {
        val client = clientFor(entry) ?: return HealthStatus.AUTH_ERROR
        return try {
            client.me()
            clearExpired(entry.serverKey)
            HealthStatus.ONLINE
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) {
                onUnauthorized(entry.serverKey)
                HealthStatus.AUTH_ERROR
            } else {
                HealthStatus.OFFLINE
            }
        } catch (e: MediaServerException) {
            HealthStatus.OFFLINE
        }
    }

    /**
     * One Quick Connect attempt: initiates, hands the code to [onCode] (the UI shows it), polls until approved,
     * then exchanges the secret for THIS device's own token. Returns [QuickConnectOutcome.Expired] when the
     * server's request lapsed so the caller can regenerate a code. Cancel the coroutine to stop (leaving the screen).
     */
    suspend fun signInWithQuickConnect(
        baseUrl: String,
        type: MediaServerType,
        onCode: (QuickConnectRequest, startedAtMs: Long) -> Unit,
    ): QuickConnectOutcome {
        val api = authApi(baseUrl, type)
        val request = try {
            api.quickConnectInitiate()
        } catch (e: MediaServerException) {
            return QuickConnectOutcome.Failed
        }
        val startedAt = nowMs()
        onCode(request, startedAt)
        var attempt = 0
        var failures = 0
        while (true) {
            delay(QuickConnectPolicy.pollDelayMs(attempt++))
            currentCoroutineContext().ensureActive()
            if (QuickConnectPolicy.isExpired(startedAt, nowMs())) return QuickConnectOutcome.Expired
            try {
                if (api.quickConnectApproved(request.secret)) {
                    return try {
                        QuickConnectOutcome.SignedIn(api.authenticateWithQuickConnect(request.secret))
                    } catch (e: MediaServerException) {
                        QuickConnectOutcome.Failed
                    }
                }
                failures = 0
            } catch (e: MediaBrowserAuthApi.QuickConnectExpired) {
                return QuickConnectOutcome.Expired
            } catch (e: MediaServerException.Http) {
                if (e.isUnauthorized) return QuickConnectOutcome.Failed
                if (++failures >= QuickConnectPolicy.MAX_CONSECUTIVE_FAILURES) return QuickConnectOutcome.Failed
            } catch (e: MediaServerException) {
                if (++failures >= QuickConnectPolicy.MAX_CONSECUTIVE_FAILURES) return QuickConnectOutcome.Failed
            }
        }
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 6_000L
        const val DISCOVERY_STAGGER_MS = 300L
    }
}
