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
 * Which headers a Stalker stream request carries, decided without the network.
 *
 * - **NEUTRAL** ([StalkerStreamIdentityPolicy.StreamIdentity.NEUTRAL], the default, and every host
 *   other than the portal's): exactly the request the TV sent before 1.21.3 (e4178acc7) — the
 *   playlist's User-Agent override, else no User-Agent at all (each engine then sends its own default,
 *   DEFAULT_STREAM_USER_AGENT), and no other header. Never a cookie, token, MAC, Referer or Origin.
 * - **Portal identity** (IDENTITY, and the stream is on the portal's host; the port may differ —
 *   portals stream from another port — but an `https` portal streamed over `http` is NOT the portal:
 *   that downgrade would send the MAC in clear): User-Agent = the override, else the MAG preset the
 *   portal accepted; X-User-Agent (the box model, not a secret); Accept. Plus the origin-bound
 *   credentials — Cookie (mac / stb_lang / timezone), Authorization Bearer token when we have one,
 *   Referer, Origin — as [OriginBoundHeaders]. Model: iptvnator's `buildStalkerExternalPlaybackHeaders`
 *   (research/reference-players/iptvnator, stalker-live-playback.utils.ts): identity to the portal's
 *   own host only.
 *
 * Redirects (the reason identity is a separate field): see [OriginBoundHeaders] — Media3 strips the
 * credentials on every hop that is not the portal host; libmpv never receives them, because FFmpeg
 * re-sends custom headers to every redirect target. So on mpv a portal-identity stream carries the
 * non-secret part (UA + X-User-Agent) only. That is a named engine gap, not a leak.
 */
object StalkerStreamHeaderPolicy {

    enum class Profile { PORTAL_IDENTITY, NEUTRAL_PLAYER }

    class Decision(
        val profile: Profile,
        /** Null: no User-Agent of our own (the engine default), as before 1.21.3. */
        val userAgent: String?,
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

    /**
     * The headers for a stream at [streamUrl]. [identity] null (the session never authenticated, or a
     * test) means there is nothing to present. [userAgentOverride] is the playlist's own User-Agent
     * (StreamUserAgentPolicy) and always wins. [streamIdentity] is the playlist's learned identity
     * ([StalkerStreamIdentityPolicy]); only IDENTITY on the portal host presents the MAG identity.
     */
    fun decide(
        streamUrl: String,
        identity: StalkerStreamIdentity?,
        userAgentOverride: String?,
        streamIdentity: StalkerStreamIdentityPolicy.StreamIdentity = StalkerStreamIdentityPolicy.DEFAULT,
    ): Decision {
        val override = userAgentOverride?.trim()?.takeIf { it.isNotEmpty() }
        val portal = identity?.portalBase?.toHttpUrlOrNull()
        if (
            identity == null || portal == null ||
            streamIdentity == StalkerStreamIdentityPolicy.StreamIdentity.NEUTRAL ||
            !isPortalHost(streamUrl, identity.portalBase)
        ) {
            return Decision(
                profile = Profile.NEUTRAL_PLAYER,
                userAgent = override,
                headers = emptyMap(),
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
