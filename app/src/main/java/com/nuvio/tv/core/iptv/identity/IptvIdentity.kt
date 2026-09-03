package com.nuvio.tv.core.iptv.identity

import java.security.MessageDigest

/**
 * Channel identity — the deterministic fingerprint a live channel keeps across provider refreshes,
 * and the key the durable personalization overlay (hide / pin / reorder / custom groups) is stored
 * under. Hand-ported twin of NuvioMobile's `features/iptv/identity/IptvIdentity` (parity of behaviour:
 * the same input must produce the same id on every platform, pinned by the shared golden vectors).
 *
 * A provider's `stream_id` renumbers (commit 9c7248641 traced a panel reissuing its whole catalog),
 * so nothing durable may be keyed on it. Identity is derived from what stays put: the tvg-id when the
 * panel supplies one, else the channel's name; the quality tag (HD / FHD / 4K) is folded in so sibling
 * feeds stay distinct, and the discriminating part of the name is folded in so two feeds sharing a
 * tvg-id (BBC ONE LONDON / NORTH) stay distinct.
 *
 * **Canon v1 — frozen, cross-platform, sync-safe.** The name is folded through a FROZEN table
 * ([CanonV1Table], Unicode 17) rather than any platform Unicode API, so the website's TypeScript, the
 * KMP clients' Kotlin, and this all compute a byte-identical id — which is what lets an edit made on
 * tuvora.co apply to the channel on the TV. A v1 id is safe to write to a synced table.
 */
object IptvIdentity {

    const val VERSION: String = "v1"

    private val QUALITY = setOf("sd", "hd", "fhd", "uhd", "4k", "8k", "hevc", "h265", "h264", "raw", "backup", "alt")

    private const val BREAK = '\u0001'

    private val table: Map<Int, String> by lazy(LazyThreadSafetyMode.PUBLICATION) { parseTable(CanonV1Table.PACKED) }

    private fun parseTable(packed: String): Map<Int, String> {
        val m = HashMap<Int, String>(1 shl 15)
        var start = 0
        while (start <= packed.length) {
            var end = packed.indexOf('\n', start)
            if (end < 0) end = packed.length
            if (end > start) {
                val line = packed.substring(start, end)
                val gt = line.indexOf('>')
                if (gt > 0) m[line.substring(0, gt).toInt(16)] = unescape(line.substring(gt + 1))
            }
            if (end == packed.length) break
            start = end + 1
        }
        return m
    }

    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == 'u') {
                sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** v1 canonical form via the frozen table; mirrors NuvioMobile's canon and canon_v1.mjs exactly. */
    fun canon(s: String): String {
        val out = StringBuilder(s.length)
        var pendingSpace = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val srcLen: Int
            val cp: Int
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                cp = 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00); srcLen = 2
            } else {
                cp = c.code; srcLen = 1
            }
            val rep = table[cp]
            if (rep == null) {
                if (pendingSpace) { out.append(' '); pendingSpace = false }
                out.append(s, i, i + srcLen)
            } else {
                var j = 0
                while (j < rep.length) {
                    val rc = rep[j]; j++
                    if (rc == BREAK) {
                        pendingSpace = out.isNotEmpty()
                    } else {
                        if (pendingSpace) { out.append(' '); pendingSpace = false }
                        out.append(rc)
                    }
                }
            }
            i += srcLen
        }
        return out.toString()
    }

    fun tvgKey(tvgId: String?): String? {
        val t = tvgId?.trim() ?: return null
        return if (t.isEmpty()) null else "e:$t"
    }

    fun tokens(name: String): List<String> = canon(name).split(' ').filter { it.isNotEmpty() }
    fun variant(name: String): String = tokens(name).firstOrNull { it in QUALITY } ?: ""
    fun nameDisc(name: String): String = tokens(name).filter { it !in QUALITY }.joinToString(" ")
    fun identityKey(name: String, tvgId: String?): String = tvgKey(tvgId) ?: ("n:" + nameDisc(name))

    fun entityId(playlistId: String, name: String, tvgId: String?): String {
        val material = playlistId + "|" + identityKey(name, tvgId) + "|" + variant(name) + "|" + nameDisc(name)
        return "fp:$VERSION:" + sha256Hex(material).substring(0, 32)
    }

    /** Durable key for a provider category (hide / reorder / rename are stored under this). */
    fun categoryKey(playlistId: String, contentType: String, name: String): String {
        val material = "$playlistId|$contentType|${canon(name)}"
        return "c:$VERSION:" + sha256Hex(material).substring(0, 32)
    }

    private fun sha256Hex(material: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(64)
        for (b in digest) {
            hex.append(((b.toInt() shr 4) and 0xF).toString(16)).append((b.toInt() and 0xF).toString(16))
        }
        return hex.toString()
    }
}
