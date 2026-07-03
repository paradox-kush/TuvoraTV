package com.nuvio.tv.core.iptv.stalker

import java.security.MessageDigest

/**
 * Pure Stalker-portal (MAG/Ministra) protocol helpers — no I/O, no Android, so they're unit-testable
 * against the documented md5/sha256 device-identity derivation and the real create_link responses.
 *
 * The full validated flow (endpoints, headers, sequence) lives in [StalkerSession] + [StalkerClient];
 * this file is only the deterministic string math those two lean on.
 */
object StalkerProtocol {

    /** Ordered endpoint candidates to probe (the user enters just the base portal URL). The first
     *  that answers a handshake with a token wins and is remembered for the session. */
    val ENDPOINT_CANDIDATES: List<String> = listOf(
        "/portal.php",
        "/stalker_portal/server/load.php",
        "/server/load.php",
        "/c/portal.php",
        "/stb/server/load.php"
    )

    /**
     * The device identity a MAG box derives from its MAC (client convention, matches the reference
     * players + the requirements doc):
     *   sn        = md5(mac).hex.upper()[:13]
     *   deviceId  = deviceId2 = sha256(mac).hex.upper()
     *   signature = sha256(mac + sn + deviceId + deviceId2).hex.upper()
     * User-supplied Serial / Device ID override the derived sn / deviceId (and feed the signature).
     */
    data class DeviceIdentity(
        val mac: String,
        val serialNumber: String,
        val deviceId: String,
        val deviceId2: String,
        val signature: String
    )

    fun deriveDeviceIdentity(
        mac: String,
        serialOverride: String? = null,
        deviceIdOverride: String? = null
    ): DeviceIdentity {
        val normalizedMac = mac.trim()
        val sn = serialOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: md5Hex(normalizedMac).uppercase().take(13)
        val deviceId = deviceIdOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: sha256Hex(normalizedMac).uppercase()
        // MAG sends device_id2 == device_id; a Device ID override applies to both.
        val deviceId2 = deviceId
        val signature = sha256Hex(normalizedMac + sn + deviceId + deviceId2).uppercase()
        return DeviceIdentity(normalizedMac, sn, deviceId, deviceId2, signature)
    }

    /**
     * The Referer a MAG box sends is the portal's `.../c/` directory, derived from the *resolved*
     * endpoint path:
     *   /portal.php                        -> {portal}/c/
     *   /stalker_portal/server/load.php    -> {portal}/stalker_portal/c/
     *   /server/load.php                   -> {portal}/c/
     *   /c/portal.php                      -> {portal}/c/
     *   /stb/server/load.php               -> {portal}/stb/c/
     * [baseUrl] is scheme+host[:port] (no trailing slash); [endpointPath] is one of
     * [ENDPOINT_CANDIDATES]. The rule: take everything before the final path segment, append `c/`.
     */
    fun refererFor(baseUrl: String, endpointPath: String): String {
        val base = baseUrl.trimEnd('/')
        val dir = endpointPath.substringBeforeLast('/', "")   // "/stalker_portal/server" or "" for "/portal.php"
            .removeSuffix("/server")                            // load.php lives under .../server, but /c/ is a sibling of server
            .trim('/')
        return if (dir.isEmpty()) "$base/c/" else "$base/$dir/c/"
    }

    /**
     * create_link returns a launcher-prefixed command, e.g.
     *   "ffmpeg http://host/live/u/p/745149.ts?play_token=xyz"
     *   "auto http://host/movie/u/p/12.mkv"
     *   "ffrt3 http://…"
     * Strip the leading launcher token by taking the LAST whitespace-separated token that parses as
     * an http(s) URL. Returns null if there's no URL at all. This covers auto/ffmpeg/ffrt/ffrt2/ffrt3
     * and any future launcher without hardcoding the list.
     */
    fun extractStreamUrl(cmd: String?): String? {
        if (cmd.isNullOrBlank()) return null
        val trimmed = cmd.trim()
        // Fast path: the whole thing is already a bare URL.
        if (isHttpUrl(trimmed)) return trimmed
        return trimmed.split(WHITESPACE).lastOrNull { isHttpUrl(it) }
    }

    private fun isHttpUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    /** MAC url-encoding for the Cookie header: only the colons become %3A (MAG convention). */
    fun encodeMacForCookie(mac: String): String = mac.trim().replace(":", "%3A")

    fun md5Hex(input: String): String = hex(MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)))
    fun sha256Hex(input: String): String = hex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    private val WHITESPACE = Regex("\\s+")
    private const val HEX_CHARS = "0123456789abcdef"
}
