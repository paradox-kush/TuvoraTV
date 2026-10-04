package com.nuvio.tv.core.diagnostics

/**
 * B116 — the one redaction policy for anything that can reach a log line.
 *
 * IPTV and add-on URLs carry credentials in many shapes: Xtream puts `user/pass` in the PATH
 * (`/live|movie|series|timeshift/<user>/<pass>/…`, or the short `/<user>/<pass>/<id>` live form),
 * M3U/EPG/panel APIs put them in the QUERY (`get.php?username=&password=`), Stalker carries
 * `mac`/`token`/`play_token`, add-on transport URLs embed debrid API keys in a config path segment
 * (`realdebrid=KEY|…`, or an opaque base64/encrypted blob), and some hosts use `user:pass@` userinfo.
 *
 * [url] keeps the scheme, host, port and the path SHAPE (so a log still says which provider, which
 * route, which stream id) and replaces every credential with [MASK]. [text] does the same for every
 * URL found inside free text (exception messages, mpv/FFmpeg lines) plus header, bearer, JWT,
 * `key=value` and MAC-address forms.
 *
 * Pure and dependency-free. The KMP apps (Mobile, Desktop, Apple TV) keep a logic-identical
 * twin (`com.nuvio.app.core.diag.LogRedaction`) tested with the same vectors.
 */
object LogRedaction {
    const val MASK = "***"

    /** Xtream route prefixes whose next two path segments are `<username>/<password>`. */
    private val XTREAM_ROUTE_PREFIXES = setOf("live", "movie", "series", "timeshift")

    /** Query/fragment/config-segment parameter names whose VALUE is a credential. */
    private val SENSITIVE_PARAM_NAMES = setOf(
        "username", "user", "login", "password", "pass", "passwd", "pwd",
        "token", "auth", "authorization", "key", "apikey", "secret", "code",
        "mac", "sn", "serial", "signature", "sig", "device_id", "device_id2", "deviceid",
        "session", "sessionid", "session_id", "stalker_password",
        // Debrid services as Stremio add-on config keys (torrentio-style `realdebrid=KEY|…`).
        "realdebrid", "alldebrid", "premiumize", "debridlink", "torbox", "offcloud", "putio",
        "easydebrid", "debrid",
    )
    private val SENSITIVE_NAME_SUFFIXES = listOf("token", "password", "secret", "apikey", "api_key", "_key", "debrid")

    /**
     * Narrower set for bare `name=value` pairs in FREE text: generic words like `key`, `code` or `user`
     * are everyday diagnostic labels there (focus keys, HTTP codes), so only unmistakable names mask.
     */
    private val FREE_TEXT_SENSITIVE_NAMES = setOf(
        "username", "password", "pass", "passwd", "pwd", "token", "apikey", "secret", "mac",
        "authorization", "cookie", "stalker_password",
        "realdebrid", "alldebrid", "premiumize", "debridlink", "torbox", "offcloud", "putio", "easydebrid",
    )

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*$")
    private val URL_IN_TEXT = Regex("(?i)\\b[a-z][a-z0-9+.\\-]*://[^\\s\"'<>`]+")
    private val TRAILING_PUNCTUATION = ".,;:!?)]}'\""
    private val STREAM_ID = Regex("^\\d+(\\.[A-Za-z0-9]{1,5})?$")
    private val CONFIG_PAIR = Regex("(^|[|&;,])([^=|&;,]+)=([^|&;,]*)")
    private val OPAQUE_TOKEN = Regex("^[A-Za-z0-9_\\-+%=]{24,}$")
    private val HEX_OR_UUID = Regex("^[0-9A-Fa-f\\-]{32,}$")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]*")
    private val HEADER_VALUE = Regex(
        "(?i)\\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token|x-access-token)(\\s*[:=]\\s*)([^\\r\\n,}\\]]+)",
    )
    private val BEARER = Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=\\-]+")
    private val BASIC = Regex("\\b(Basic)\\s+[A-Za-z0-9+/]{8,}={0,2}")
    private val FREE_TEXT_PAIR = Regex("(?i)\\b([a-z_][a-z0-9_\\-]*)(\\s*=\\s*)([^\\s&|,;\"'}\\])]+)")
    private val MAC_ADDRESS = Regex("\\b[0-9A-Fa-f]{2}(?:[:\\-][0-9A-Fa-f]{2}){5}\\b")

    /** Redacts one URL. Not-a-URL input (e.g. a Stalker `ffmpeg http://…` cmd) is treated as [text]. */
    fun url(raw: String?): String {
        if (raw.isNullOrEmpty()) return raw.orEmpty()
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0 || !SCHEME.matches(raw.substring(0, schemeEnd))) return text(raw)
        val scheme = raw.substring(0, schemeEnd)
        val rest = raw.substring(schemeEnd + 3)

        val hashIndex = rest.indexOf('#')
        val beforeFragment = if (hashIndex >= 0) rest.substring(0, hashIndex) else rest
        val fragment = if (hashIndex >= 0) rest.substring(hashIndex + 1) else null
        val queryIndex = beforeFragment.indexOf('?')
        val authorityAndPath = if (queryIndex >= 0) beforeFragment.substring(0, queryIndex) else beforeFragment
        val query = if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1) else null
        val slash = authorityAndPath.indexOf('/')
        val authority = if (slash >= 0) authorityAndPath.substring(0, slash) else authorityAndPath
        val path = if (slash >= 0) authorityAndPath.substring(slash) else ""
        val at = authority.lastIndexOf('@')
        val hostPort = if (at >= 0) "$MASK@" + authority.substring(at + 1) else authority

        return buildString {
            append(scheme).append("://").append(hostPort).append(redactPath(path))
            if (query != null) append('?').append(redactQuery(query))
            if (fragment != null) {
                append('#').append(if ('=' in fragment) redactQuery(fragment) else fragment)
            }
        }
    }

    /** Redacts every URL and credential form found inside free text. */
    fun text(message: String?): String {
        if (message.isNullOrEmpty()) return message.orEmpty()
        if (!mightCarrySecret(message)) return message
        var out = URL_IN_TEXT.replace(message) { match ->
            val value = match.value
            var end = value.length
            while (end > 0 && value[end - 1] in TRAILING_PUNCTUATION) end--
            url(value.substring(0, end)) + value.substring(end)
        }
        out = HEADER_VALUE.replace(out) { it.groupValues[1] + it.groupValues[2] + MASK }
        out = BEARER.replace(out) { it.groupValues[1] + " " + MASK }
        out = BASIC.replace(out) { it.groupValues[1] + " " + MASK }
        out = JWT.replace(out, MASK)
        out = FREE_TEXT_PAIR.replace(out) { match ->
            if (match.groupValues[1].lowercase() in FREE_TEXT_SENSITIVE_NAMES) {
                match.groupValues[1] + match.groupValues[2] + MASK
            } else {
                match.value
            }
        }
        out = MAC_ADDRESS.replace(out, MASK)
        return out
    }

    private fun mightCarrySecret(message: String): Boolean =
        "://" in message || '=' in message || "earer" in message || "Basic " in message ||
            "eyJ" in message || message.count { it == ':' || it == '-' } >= 5 ||
            message.contains("authorization", ignoreCase = true) || message.contains("cookie", ignoreCase = true)

    private fun redactPath(path: String): String {
        if (path.length <= 1) return path
        val parts = path.substring(1).split('/').toMutableList()
        val masked = BooleanArray(parts.size)
        val first = parts[0].lowercase()
        if (first in XTREAM_ROUTE_PREFIXES && parts.size >= 4) {
            masked[1] = true
            masked[2] = true
        } else if (parts.size == 3 && STREAM_ID.matches(parts[2]) && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
            // Xtream short live form: /<user>/<pass>/<streamId>[.ext]
            masked[0] = true
            masked[1] = true
        }
        for (i in parts.indices) {
            parts[i] = if (masked[i]) MASK else redactSegment(parts[i])
        }
        return "/" + parts.joinToString("/")
    }

    private fun redactSegment(segment: String): String {
        if (segment.isEmpty()) return segment
        if (JWT.matches(segment)) return MASK
        val decoded = percentDecode(segment)
        if ('=' in decoded) {
            val hasSensitive = CONFIG_PAIR.findAll(decoded).any { isSensitiveName(it.groupValues[2]) }
            if (hasSensitive) {
                // Encoded config: masking in place would need re-encoding, so the whole segment goes.
                if (decoded != segment) return MASK
                return CONFIG_PAIR.replace(segment) { match ->
                    if (isSensitiveName(match.groupValues[2])) {
                        match.groupValues[1] + match.groupValues[2] + "=" + MASK
                    } else {
                        match.value
                    }
                }
            }
        }
        if (isOpaqueToken(segment)) return MASK
        return segment
    }

    private fun isOpaqueToken(segment: String): Boolean {
        if (HEX_OR_UUID.matches(segment)) return true
        if (!OPAQUE_TOKEN.matches(segment)) return false
        // Opaque blobs (base64 configs, encrypted add-on configs) mix case and digits; readable
        // slugs ("some-movie-title-2024") are lowercase and must stay legible.
        return segment.any { it.isDigit() } && segment.any { it.isUpperCase() } && segment.any { it.isLowerCase() }
    }

    private fun redactQuery(query: String): String =
        query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@joinToString pair
            val name = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            when {
                isSensitiveName(percentDecode(name)) -> "$name=$MASK"
                // A nested URL (Stalker `cmd=ffmpeg http://…?play_token=…`) masks wholesale if it
                // carries anything secret, rather than re-encoding a partially redacted value.
                value.isNotEmpty() && percentDecode(value).let { it.contains("://") && text(it) != it } -> "$name=$MASK"
                else -> pair
            }
        }

    private fun isSensitiveName(rawName: String): Boolean {
        val name = rawName.trim().lowercase()
        if (name.isEmpty()) return false
        return name in SENSITIVE_PARAM_NAMES || SENSITIVE_NAME_SUFFIXES.any { name.endsWith(it) }
    }

    private fun percentDecode(value: String): String {
        if ('%' !in value && '+' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.add(hex.toByte())
                    i += 3
                    continue
                }
            }
            if (c == '+') {
                bytes.add(' '.code.toByte())
            } else {
                c.toString().encodeToByteArray().forEach { bytes.add(it) }
            }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }
}
