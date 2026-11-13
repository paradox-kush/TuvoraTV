package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.playback.core.OriginBoundHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The identity a Stalker session presents to its portal, captured right after a create_link so the
 * stream request can present the same box. Holds the MAC cookie and the bearer token: never printed.
 */
class StalkerStreamIdentity(
    /** The portal base, scheme://host[:port] (StalkerProtocol.normalizePortalBase). */
    val portalBase: String,
    /** The `.../c/` Referer the session sends on its API calls. */
    val referer: String,
    /** `mac=…; stb_lang=en; timezone=Europe/London`, as the API calls send it (MAC URL-encoded). */
    val cookie: String,
    /** The MAG preset this portal accepted (StalkerMagPresets). */
    val magUserAgent: String,
    val magXUserAgent: String,
    /** The handshake token; null before a handshake (a static-cmd row can play without one). */
    val token: String?,
) {
    override fun toString(): String = "StalkerStreamIdentity([REDACTED])"
}

/**
 * Which headers a Stalker stream request carries (B259), decided without the network.
 *
 * Model: iptvnator's `buildStalkerExternalPlaybackHeaders` (research/reference-players/iptvnator,
 * stalker-live-playback.utils.ts) — the portal identity goes ONLY to the portal's own host; any other
 * stream host gets a credential-free player profile. Implemented here in our own terms:
 *
 * - **Portal host** (same host as the portal; the port may differ — portals stream from another port —
 *   but an `https` portal streamed over `http` is NOT the portal: that downgrade would send the MAC in
 *   clear): User-Agent = the playlist's override, else the MAG preset the portal accepted; X-User-Agent
 *   (the box model, not a secret); Accept. Plus the origin-bound credentials — Cookie (mac / stb_lang /
 *   timezone), Authorization Bearer token when we have one, Referer, Origin — as [OriginBoundHeaders].
 * - **Any other host**: User-Agent = the override, else an honest neutral player UA ([neutralUserAgent],
 *   never a browser or a MAG box we are not presenting to that host); Accept. Never a cookie, token,
 *   MAC, Referer or Origin.
 *
 * Deliberately NOT sent, unlike iptvnator's cross-origin profile:
 *  - `Range: bytes=0-` — Media3 and FFmpeg own Range for seeks and resumes; a fixed one would conflict.
 *  - `Icy-MetaData: 1` — both engines already ask for ICY metadata where they can strip it (Media3's
 *    progressive loader, FFmpeg's `icy` option, default on). Forcing it onto an HLS segment request
 *    would let a server interleave metadata into bytes Media3 then parses as media.
 *
 * Redirects (the reason identity is a separate field): see [OriginBoundHeaders] — Media3 strips the
 * credentials on every hop that is not the portal host; libmpv never receives them, because FFmpeg
 * re-sends custom headers to every redirect target. So on mpv a portal-host stream carries the
 * non-secret part (UA + X-User-Agent) only. That is a named engine gap, not a leak.
 */
object StalkerStreamHeaderPolicy {

    enum class Profile { PORTAL_IDENTITY, NEUTRAL_PLAYER }

    class Decision(
        val profile: Profile,
        val userAgent: String,
        /** Sent by every engine on every hop: nothing secret. */
        val headers: Map<String, String>,
        /** Portal-host-only credentials; null for [Profile.NEUTRAL_PLAYER]. */
        val originBound: OriginBoundHeaders?,
    ) {
        override fun toString(): String =
            "StalkerStreamHeaderPolicy.Decision(profile=$profile, headerNames=${headers.keys.sorted()}, " +
                "originBound=$originBound)"
    }

    const val ACCEPT = "*/*"

    /** Honest: names the app and the platform, claims to be neither a browser nor a set-top box. */
    fun neutralUserAgent(appVersion: String): String =
        "Tuvora/${appVersion.trim().ifEmpty { "1" }} (Linux; Android TV)"

    /**
     * The headers for a stream at [streamUrl]. [identity] null (the session never authenticated, or a
     * test) means there is nothing to present: the neutral profile. [userAgentOverride] is the
     * playlist's own User-Agent (StreamUserAgentPolicy) and always wins.
     */
    fun decide(
        streamUrl: String,
        identity: StalkerStreamIdentity?,
        userAgentOverride: String?,
        appVersion: String,
    ): Decision {
        val override = userAgentOverride?.trim()?.takeIf { it.isNotEmpty() }
        val portal = identity?.portalBase?.toHttpUrlOrNull()
        if (identity == null || portal == null || !isPortalHost(streamUrl, identity.portalBase)) {
            return Decision(
                profile = Profile.NEUTRAL_PLAYER,
                userAgent = override ?: neutralUserAgent(appVersion),
                headers = linkedMapOf("Accept" to ACCEPT),
                originBound = null,
            )
        }
        val bound = linkedMapOf("Cookie" to identity.cookie)
        identity.token?.trim()?.takeIf { it.isNotEmpty() }?.let { bound["Authorization"] = "Bearer $it" }
        identity.referer.trim().takeIf { it.isNotEmpty() }?.let { bound["Referer"] = it }
        bound["Origin"] = originOf(portal)
        return Decision(
            profile = Profile.PORTAL_IDENTITY,
            userAgent = override ?: identity.magUserAgent,
            headers = linkedMapOf("X-User-Agent" to identity.magXUserAgent, "Accept" to ACCEPT),
            originBound = OriginBoundHeaders(
                host = portal.host,
                secureOrigin = portal.scheme == "https",
                headers = bound,
            ),
        )
    }

    /**
     * The non-secret part of [decide], for players that apply one flat header map to every hop (the
     * VOD/episode player, the legacy lanes): never a cookie, token, Referer or Origin.
     */
    fun portableHeaders(
        streamUrl: String,
        identity: StalkerStreamIdentity?,
        userAgentOverride: String?,
        appVersion: String,
    ): Map<String, String> {
        val decision = decide(streamUrl, identity, userAgentOverride, appVersion)
        return linkedMapOf("User-Agent" to decision.userAgent) + decision.headers
    }

    /**
     * [stream] with [portable] replacing any same-named request header (case-insensitive); other
     * request headers and the response headers are kept. For the VOD/episode player.
     */
    fun withPortableHeaders(
        stream: com.nuvio.tv.domain.model.Stream,
        portable: Map<String, String>,
    ): com.nuvio.tv.domain.model.Stream {
        if (portable.isEmpty()) return stream
        val hints = stream.behaviorHints ?: com.nuvio.tv.domain.model.StreamBehaviorHints(null, null, null, null)
        val old = hints.proxyHeaders
        val kept = old?.request.orEmpty().filterKeys { name -> portable.keys.none { it.equals(name, ignoreCase = true) } }
        return stream.copy(
            behaviorHints = hints.copy(
                proxyHeaders = com.nuvio.tv.domain.model.ProxyHeaders(request = kept + portable, response = old?.response),
            ),
        )
    }

    /**
     * Same host as the portal (case-insensitive; any port), over http(s), and not an http stream of an
     * https portal. Unparseable URLs are never the portal.
     */
    fun isPortalHost(streamUrl: String, portalBase: String): Boolean {
        val stream = streamUrl.trim().toHttpUrlOrNull() ?: return false
        val portal = portalBase.trim().toHttpUrlOrNull() ?: return false
        return OriginBoundHeaders(portal.host, portal.scheme == "https", emptyMap())
            .allowsHop(stream.scheme, stream.host)
    }

    private fun originOf(portal: okhttp3.HttpUrl): String {
        val defaultPort = okhttp3.HttpUrl.defaultPort(portal.scheme)
        val host = if (':' in portal.host) "[${portal.host}]" else portal.host
        return if (portal.port == defaultPort) "${portal.scheme}://$host" else "${portal.scheme}://$host:${portal.port}"
    }
}
