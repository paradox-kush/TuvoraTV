package com.nuvio.tv.core.analytics

/** Last-mile privacy guard applied before PostHog persists or uploads an event. */
internal object PostHogPrivacy {
    const val GEOIP_DISABLE_PROPERTY = "\$geoip_disable"
    const val DEEP_LINK_EVENT = "Deep Link Opened"

    private val urlPattern = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s\"'<>]+""")
    private val authorizationHeaderPattern = Regex("""(?i)\b(?:bearer|basic)\s+[a-z0-9._~+/=-]+""")
    // Media-server credentials (Jellyfin/Emby ApiKey / api_key, Plex X-Plex-Token) are named explicitly
    // even where a looser alternative would already catch them, so the list stays honest if that changes.
    private val authValuePattern = Regex(
        """(?i)(\b(?:code|state|access_token|refresh_token|token|authorization|password|secret|apikey|api_key|x-plex-token)=)[^&\s\"'<>]+""",
    )
    // `X-Emby-Token: <value>` / `X-Plex-Token: <value>` header lines (a colon, not `=`).
    private val tokenHeaderPattern = Regex(
        """(?i)(\b(?:x-emby-token|x-plex-token|x-mediabrowser-token)\s*:\s*)[^\s,;\"'<>]+""",
    )
    // The MediaBrowser Authorization header carries the session token as a QUOTED pair: Token="...".
    private val quotedTokenPattern = Regex("""(?i)(\btoken=")[^"]*(")""")

    private val sensitiveKeys = setOf(
        "url", "uri", "href", "referrer", "\$referrer", "code", "state", "token",
        "access_token", "refresh_token", "authorization", "password", "secret", "cookie",
        "api_key", "apikey",
    )

    fun shouldDropEvent(event: String): Boolean = event.equals(DEEP_LINK_EVENT, ignoreCase = true)

    fun sanitize(properties: Map<String, *>): Map<String, Any> =
        sanitizeMap(properties).toMutableMap().apply { put(GEOIP_DISABLE_PROPERTY, true) }

    private fun sanitizeMap(properties: Map<String, *>): Map<String, Any> = buildMap {
        for ((key, value) in properties) {
            if (isSensitiveKey(key)) continue
            sanitizeValue(value)?.let { put(key, it) }
        }
    }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        null -> null
        is String -> redactString(value)
        is Map<*, *> -> sanitizeMap(
            value.entries.mapNotNull { (key, nested) ->
                (key as? String)?.let { it to nested }
            }.toMap(),
        )
        is Iterable<*> -> value.mapNotNull(::sanitizeValue)
        is Array<*> -> value.mapNotNull(::sanitizeValue)
        else -> value
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().replace('-', '_')
        return normalized in sensitiveKeys ||
            normalized.endsWith("_url") ||
            normalized.endsWith("_uri") ||
            "token" in normalized ||
            "password" in normalized ||
            "secret" in normalized ||
            "authorization" in normalized ||
            "cookie" in normalized
    }

    private fun redactString(value: String): String {
        val withoutUrls = urlPattern.replace(value, "[redacted-url]")
        val withoutAuthorization = authorizationHeaderPattern.replace(withoutUrls, "[redacted-auth]")
        val withoutQuotedToken = quotedTokenPattern.replace(withoutAuthorization) { match ->
            "${match.groupValues[1]}[redacted]${match.groupValues[2]}"
        }
        val withoutTokenHeader = tokenHeaderPattern.replace(withoutQuotedToken) { match ->
            "${match.groupValues[1]}[redacted]"
        }
        return authValuePattern.replace(withoutTokenHeader) { match ->
            "${match.groupValues[1]}[redacted]"
        }
    }
}
