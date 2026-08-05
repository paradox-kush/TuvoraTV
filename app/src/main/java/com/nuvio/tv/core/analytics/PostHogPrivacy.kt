package com.nuvio.tv.core.analytics

/** Last-mile privacy guard applied before PostHog persists or uploads an event. */
internal object PostHogPrivacy {
    const val GEOIP_DISABLE_PROPERTY = "\$geoip_disable"
    const val DEEP_LINK_EVENT = "Deep Link Opened"

    private val urlPattern = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s\"'<>]+""")
    private val authorizationHeaderPattern = Regex("""(?i)\b(?:bearer|basic)\s+[a-z0-9._~+/=-]+""")
    private val authValuePattern = Regex(
        """(?i)(\b(?:code|state|access_token|refresh_token|token|authorization|password|secret)=)[^&\s\"'<>]+""",
    )

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
        return authValuePattern.replace(withoutAuthorization) { match ->
            "${match.groupValues[1]}[redacted]"
        }
    }
}
