// Portions of this file are derived from Plezy (https://github.com/edde746/plezy), GPL-3.0,
// lib/services/jellyfin_auth_header.dart at commit 05ef93c55b0a6e8a35683762c2803e1756aaed1c,
// ported to Kotlin. Tuvora is GPL-3.0 as well.
package com.nuvio.tv.core.mediaserver.client.mediabrowser

/**
 * What this install tells a server about itself. Both products key sessions and access tokens by
 * Client + Device + DeviceId + Version together; an unauthenticated entry point (Quick Connect, login)
 * REQUIRES all four or the server throws. [deviceId] is a stable per-install random id - never synced.
 */
internal data class MediaBrowserClientIdentity(
    val client: String,
    val device: String,
    val deviceId: String,
    val version: String,
)

internal object MediaBrowserAuth {
    private val controlCharacters = Regex("[\\u0000-\\u001F\\u007F-\\u009F]")

    /**
     * The `Authorization` header value both dialects understand:
     * `MediaBrowser Client="..", Device="..", DeviceId="..", Version="..", Token=".."`. Every value is
     * percent-encoded (the server reverses it) - that is what keeps a device name like `Bjorn's PC` sendable
     * and removes the quote/comma/`=` hazards the header grammar has no escape for. Without a [token] the
     * `Token` pair is omitted (the tokenless form is what Quick Connect initiation and login send).
     * Throws when the [identity]'s device id is blank: a shared fallback would collide across installs.
     */
    fun authorization(identity: MediaBrowserClientIdentity, token: String? = null): String {
        val deviceId = meaningful(identity.deviceId)
        require(deviceId.isNotEmpty()) { "a media-server session needs a stable device id" }
        val client = meaningful(identity.client).ifEmpty { "Tuvora" }
        val device = meaningful(identity.device).ifEmpty { client }
        val version = meaningful(identity.version).ifEmpty { "1.0" }
        val tokenValue = token?.let(::meaningful).orEmpty()
        fun pair(name: String, value: String) = "$name=\"${percentEncode(value)}\""
        val parts = buildList {
            add(pair("Client", client))
            add(pair("Device", device))
            add(pair("DeviceId", deviceId))
            add(pair("Version", version))
            if (tokenValue.isNotEmpty()) add(pair("Token", tokenValue))
        }
        return "MediaBrowser ${parts.joinToString(", ")}"
    }

    /**
     * The headers an authenticated request carries: always the MediaBrowser `Authorization`; Emby also
     * gets its documented `X-Emby-Token`. Content negotiation headers are the HTTP layer's.
     */
    fun authenticatedHeaders(dialect: MediaBrowserDialect, identity: MediaBrowserClientIdentity, token: String): Map<String, String> =
        buildMap {
            put("Authorization", authorization(identity, token))
            dialect.extraTokenHeader?.let { put(it, token) }
        }

    private fun meaningful(value: String): String = value.replace(controlCharacters, "").trim()
}
