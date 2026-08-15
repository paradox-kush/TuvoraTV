package com.nuvio.tv.core.iptv.stalker

/**
 * STATIC vs MINT: whether a Stalker row's browse-time `cmd` is directly playable, or must be
 * resolved through `create_link` at play time.
 *
 * The rule is the portal's own (`/c/player.js`, mirrored by Kodi pvr.stalker's ChannelManager and
 * iptvnator, and confirmed in the reference server's `itv.class.php`):
 *
 *     use_http_tmp_link == 1 || use_load_balancing == 1  →  create_link
 *     otherwise                                          →  play the row's static cmd
 *
 * create_link on an unflagged row is a pure wasted round trip — the server returns the cmd
 * UNCHANGED — and every removed request matters against rate-limiting portals, token rotation
 * under stampede, and max_connections enforcement. Flagged rows get a short-TTL token (default
 * 5 s) and their LISTING cmd is a masked placeholder (`ffrt http://<proxy>/ch/<id>`,
 * `udp://ch/<id>`), so playing them statically fails hard: the flags always win.
 *
 * Every further guard only ever pushes a row BACK onto the create_link path (today's behaviour),
 * so a wrong verdict cannot regress a portal that works now:
 *  - no flag evidence (legacy cached rows carry none) → mint;
 *  - after the launcher strip, `rtp://`/`udp://` targets are static — multicast carries no token,
 *    and the server returns those unchanged even inside its tokenizing branch;
 *  - any other non-http(s) scheme, a relative or query-only cmd (the VOD has_files rewrite), or an
 *    http url with no authority (stock masking with an empty `stream_proxy`) → mint;
 *  - loopback/portal-local hosts → mint: `http://localhost/ch/291` is an instruction to the
 *    portal, never an address this player could open.
 */
object StalkerPlaybackLinkPolicy {

    sealed class Decision {
        /** Play [url] directly — no create_link round trip. */
        data class Static(val url: String) : Decision()

        /** Resolve via create_link (today's path — also the answer to every doubt). */
        object Mint : Decision() {
            override fun toString(): String = "Mint"
        }
    }

    /**
     * [useHttpTmpLink]/[useLoadBalancing]: null = the row carried no such key (no evidence).
     * [cmd] is the raw browse-time command, launcher prefix and all.
     */
    fun decide(useHttpTmpLink: Boolean?, useLoadBalancing: Boolean?, cmd: String?): Decision {
        // The flags are the rule: known-true means the listing cmd is a masked placeholder.
        if (useHttpTmpLink == true || useLoadBalancing == true) return Decision.Mint
        // Stock portals send both flags on every row, so a row with NEITHER key predates flag
        // storage (legacy cache) — absence of evidence keeps minting.
        if (useHttpTmpLink == null && useLoadBalancing == null) return Decision.Mint

        val stripped = StalkerProtocol.stripLauncherPrefix(cmd ?: return Decision.Mint)
        if (stripped.isBlank()) return Decision.Mint

        // Multicast is inherently static; a token can neither ride nor gate it.
        if (hasScheme(stripped, "rtp") || hasScheme(stripped, "udp")) {
            return Decision.Static(stripped.split(WHITESPACE).first())
        }

        // http(s) is the only other scheme a client can open on its own. extractStreamUrl returns
        // null for portal pseudo-schemes (ffrt4://…), relative paths and bare query strings —
        // exactly the shapes only create_link can resolve.
        val url = StalkerProtocol.extractStreamUrl(cmd) ?: return Decision.Mint
        val host = hostOf(url) ?: return Decision.Mint   // empty authority: http:///ch/1
        if (isPortalLocalHost(host)) return Decision.Mint
        return Decision.Static(url)
    }

    private fun hasScheme(s: String, scheme: String): Boolean =
        s.startsWith("$scheme://", ignoreCase = true)

    /**
     * The lowercase host of an http(s) [url], or null when the authority is empty/unreadable.
     * A trailing dot is the DNS root and resolves identically (`http://localhost./ch/1`).
     */
    private fun hostOf(url: String): String? {
        val authority = url.substringAfter("://", "")
            .takeWhile { it != '/' && it != '?' && it != '#' }
            .substringAfterLast('@')
        if (authority.isEmpty()) return null
        val host = if (authority.startsWith("[")) {
            authority.removePrefix("[").substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
        return host.trim().lowercase().removeSuffix(".").takeIf { it.isNotEmpty() }
    }

    /**
     * Whether [host] can only mean the machine resolving it — the portal when it wrote the cmd,
     * this player if taken literally. RFC 6761 reserves `localhost` AND every name under
     * `.localhost`; IPv4 reserves all of 127.0.0.0/8, not just 127.0.0.1.
     */
    private fun isPortalLocalHost(host: String): Boolean {
        if (host == "localhost" || host == "localhost.localdomain" || host.endsWith(".localhost")) return true
        if (host == "0.0.0.0") return true
        if (IPV4_LOOPBACK.matches(host)) return true
        return isIpv6Local(host)
    }

    /** IPv6 loopback/unspecified incl. IPv4-mapped forms: ::1, ::, ::ffff:127.0.0.1, ::ffff:7f00:1. */
    private fun isIpv6Local(host: String): Boolean {
        if (!host.contains(':')) return false
        val tail = host.substringAfterLast(':')
        if (tail.contains('.')) {
            // Dotted-mapped form. Only the mapped/unspecified prefixes count.
            val head = host.removeSuffix(tail)
            if (head != "::ffff:" && head != "::") return false
            return IPV4_LOOPBACK.matches(tail) || tail == "0.0.0.0"
        }
        val groups = expandIpv6(host) ?: return false
        if (groups.all { it == 0 }) return true                              // :: (unspecified)
        if (groups.take(7).all { it == 0 } && groups[7] == 1) return true    // ::1
        if (groups.take(5).all { it == 0 } && groups[5] == 0xFFFF) {
            // IPv4-mapped as hex groups: 127.0.0.1 is 7f00:0001 — /8 shares the 0x7f high byte.
            return (groups[6] ushr 8) == 0x7F || (groups[6] == 0 && groups[7] == 0)
        }
        return false
    }

    /** [host] as 8 hex groups, or null when it isn't parseable IPv6. */
    private fun expandIpv6(host: String): IntArray? {
        val gap = host.indexOf("::")
        val headPart = if (gap >= 0) host.substring(0, gap) else host
        val tailPart = if (gap >= 0) host.substring(gap + 2) else ""
        val head = if (headPart.isEmpty()) emptyList() else headPart.split(':')
        val tail = if (tailPart.isEmpty()) emptyList() else tailPart.split(':')
        if (gap < 0 && head.size != 8) return null
        if (gap >= 0 && head.size + tail.size > 7) return null
        val groups = head + List(8 - head.size - tail.size) { "0" } + tail
        val out = IntArray(8)
        for ((i, g) in groups.withIndex()) {
            val v = g.toIntOrNull(16)?.takeIf { it in 0..0xFFFF } ?: return null
            out[i] = v
        }
        return out
    }

    private val WHITESPACE = Regex("\\s+")
    private val IPV4_LOOPBACK = Regex("""127\.\d{1,3}\.\d{1,3}\.\d{1,3}""")
}
