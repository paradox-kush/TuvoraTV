package com.nuvio.tv.core.mediaserver.client

import com.nuvio.tv.core.mediaserver.policy.CertTrustPolicy
import com.nuvio.tv.core.mediaserver.store.MediaServerStorage
import com.nuvio.tv.core.mediaserver.store.MediaServerTrustStore

/** A certificate failure the platform trust glue saw while a request was failing - what the UI turns into "trust this certificate?". */
internal data class TlsFailure(val authority: String, val kind: CertTrustPolicy.FailureKind, val fingerprint: String)

/**
 * The seam between the platform TLS stacks (OkHttp's trust manager, Darwin's server-trust challenge) and the
 * shared [CertTrustPolicy]: the platform reports the facts of one handshake through [check] and enforces the
 * answer; a refused one is remembered for [takeFailure] so the failing request can surface a
 * [MediaServerException.CertificateUntrusted] (the user sees the fingerprint and may pin it).
 */
internal class MediaServerTrust(private val pins: MediaServerTrustStore) {
    private val lock = Any()
    private val failures = mutableMapOf<String, TlsFailure>()

    /** True when the handshake may proceed: the system trusted the chain, or this exact certificate was pinned. */
    fun check(
        authority: String,
        systemTrusted: Boolean,
        failure: CertTrustPolicy.FailureKind?,
        fingerprint: String,
    ): Boolean {
        val host = authority.substringBeforeLast(':')
        val verdict = CertTrustPolicy.decide(systemTrusted, failure, fingerprint, pins.pinned(authority), host)
        if (verdict is CertTrustPolicy.Verdict.Accept) return true
        val kind = when (verdict) {
            is CertTrustPolicy.Verdict.Reject -> verdict.failure
            else -> failure ?: CertTrustPolicy.FailureKind.OTHER
        }
        synchronized(lock) { failures[authority.lowercase()] = TlsFailure(authority, kind, fingerprint) }
        return false
    }

    /** The recorded failure for [authority], if the last handshake there was refused (cleared by reading it). */
    fun takeFailure(authority: String): TlsFailure? = synchronized(lock) { failures.remove(authority.lowercase()) }

    /** The failure as the exception the UI branches on - only when trust-on-first-use may be offered for it. */
    fun asException(authority: String): MediaServerException.CertificateUntrusted? {
        val f = takeFailure(authority) ?: return null
        val host = authority.substringBeforeLast(':')
        if (!CertTrustPolicy.canOfferTrust(f.kind, host)) return null
        return MediaServerException.CertificateUntrusted(authority, f.kind.name, f.fingerprint)
    }

    fun pinnedFingerprint(authority: String): String? = pins.pinned(authority)

    /** The user accepted this certificate. */
    fun pin(authority: String, fingerprint: String) = pins.pin(authority, fingerprint)

    fun unpin(authority: String) = pins.unpin(authority)

    /** Account wipe: every pinned certificate (they name the user's servers). */
    fun clearAll() = pins.clearAll()

    companion object {
        /** The device-wide instance, persisted through the platform's plain storage. */
        val shared: MediaServerTrust by lazy {
            MediaServerTrust(
                MediaServerTrustStore(
                    load = { MediaServerStorage.loadTrustJson() },
                    save = { MediaServerStorage.saveTrustJson(it) },
                    erase = { MediaServerStorage.removeTrustJson() },
                ),
            )
        }
    }
}

