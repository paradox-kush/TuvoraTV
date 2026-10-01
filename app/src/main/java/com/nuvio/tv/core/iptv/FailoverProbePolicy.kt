package com.nuvio.tv.core.iptv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What a validation probe's answer means (Step 0.3b addendum). */
enum class ProbeVerdict {
    /** This server is the playlist's panel and the account works: it may win the race. */
    VALID,
    /** The account/credentials are the problem — the same on every server. Surface it, no failover. */
    DEFINITIVE,
    /** A 2xx that is not the panel (parked domain, CDN error page, empty/garbage body). Fail over. */
    INVALID,
}

/** A 2xx the probe judged not to be the playlist's panel. Fails over. */
class FailoverInvalidResponseException(message: String) : IllegalStateException(message)

/** The probe proved the account itself is refused (auth=0, Expired/Banned/Disabled). Never fails over. */
class FailoverAuthRejectedException(message: String) : IllegalStateException(message)

/**
 * Step 0.3b — the pure validity rules of the per-type validation probes. A server only wins the
 * failover race when its probe is [ProbeVerdict.VALID]. Golden cases: `FailoverRaceGolden` probe tables
 * (shared verbatim with NuvioMobile/NuvioDesktop; this is their TV twin).
 */
object FailoverProbePolicy {

    /** user_info.status values (case-insensitive) that make an auth=1 login a refusal. */
    private val REFUSED_STATUSES = setOf("expired", "banned", "disabled")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * An Xtream no-action `player_api.php` body.
     *  - auth=0 (or "0"/false) -> DEFINITIVE, even without a server_info block (that is how panels answer bad credentials)
     *  - not a JSON object, no `user_info` object, no `server_info` object, or auth absent -> INVALID
     *  - auth=1 with status Expired/Banned/Disabled -> DEFINITIVE
     *  - otherwise (auth=1) -> VALID
     */
    fun xtreamLogin(body: String): ProbeVerdict {
        val root = runCatching { json.parseToJsonElement(body.trimStart { it == '\uFEFF' || it.isWhitespace() }) }.getOrNull() as? JsonObject
            ?: return ProbeVerdict.INVALID
        val user = root["user_info"] as? JsonObject ?: return ProbeVerdict.INVALID
        val auth = authOf(user["auth"])
        if (auth == 0L) return ProbeVerdict.DEFINITIVE
        if (root["server_info"] !is JsonObject) return ProbeVerdict.INVALID
        if (auth != 1L) return ProbeVerdict.INVALID
        val status = (user["status"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        return if (status in REFUSED_STATUSES) ProbeVerdict.DEFINITIVE else ProbeVerdict.VALID
    }

    /** The first bytes of an M3U probe (decoded): after BOM/whitespace they must begin `#EXTM3U`. */
    fun m3uPrefix(prefix: String): ProbeVerdict {
        val head = prefix.trimStart { it == '﻿' || it.isWhitespace() }
        return if (head.startsWith("#EXTM3U")) ProbeVerdict.VALID else ProbeVerdict.INVALID
    }

    /** Stalker handshake: valid only with a non-blank token. */
    fun stalkerHandshake(token: String?): ProbeVerdict =
        if (token.isNullOrBlank()) ProbeVerdict.INVALID else ProbeVerdict.VALID

    /** `auth` arrives as 1, "1", true, 0, "0", false depending on the panel. */
    private fun authOf(el: JsonElement?): Long? {
        val p = el as? JsonPrimitive ?: return null
        p.longOrNull?.let { return it }
        return when (p.contentOrNull?.trim()?.lowercase()) {
            "true" -> 1L
            "false" -> 0L
            else -> null
        }
    }

    /** The probe verdict as the exception the failover classifier understands; null = VALID. */
    fun toFailure(verdict: ProbeVerdict, what: String): Throwable? = when (verdict) {
        ProbeVerdict.VALID -> null
        ProbeVerdict.DEFINITIVE -> FailoverAuthRejectedException("$what: the account was refused")
        ProbeVerdict.INVALID -> FailoverInvalidResponseException("$what: not the playlist's server")
    }
}

/** Host key for the "never two concurrent requests to one host" rule: lowercased host, no port/userinfo/path. */
internal object FailoverHostKey {
    fun of(url: String): String {
        val afterScheme = url.trim().substringAfter("://", url.trim())
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.lowercase()
    }
}
