package com.nuvio.tv.core.epg

/**
 * F10 — strips IPTV packaging from a channel name: country prefixes (`UK:`, `|US|`, `[DE]`,
 * `FR ▎`), quality tags (`HD`, `FHD`, `4K`, `HEVC`, `(1080p)`, `ᴴᴰ`), decorations (`★`, emoji,
 * `[Not 24/7]`) and whatever extra tags the user lists for their playlist (`VIP`, `|PRIME|`).
 *
 * ONE normaliser, two consumers:
 *  - the guide matcher ([GuideChannelMatcher] / [EpgChannelIndex]) cleans every provider name
 *    before comparing it with guide display-names (B10), and
 *  - the browse/guide surfaces show the cleaned name when the user turns "Clean up channel names"
 *    on for a playlist (display is opt-in; matching always cleans).
 *
 * Deliberately conservative for DISPLAY: a country code is only stripped when a separator follows
 * it (`UK: BBC One`, `|UK| BBC One`, `[UK] BBC One`), never from `AL JAZEERA`; quality is only
 * stripped from the END (or straight after a stripped prefix), never from the middle; identity
 * words stay (`+1`, `Plus`, `One`, `2`, `(East)`). If cleaning would leave nothing, the raw name
 * is returned. Pure and allocation-light; identical vectors in NuvioMobile `features/epg` twin (KMP: Android, iOS, Desktop, Apple TV).
 */
object ChannelNameCleaner {

    data class Rules(
        val stripCountryPrefix: Boolean = true,
        val stripQuality: Boolean = true,
        val stripDecorations: Boolean = true,
        /** Extra user tags, matched case-insensitively (whole word for alphanumeric tags). */
        val userTags: List<String> = emptyList(),
    ) {
        companion object {
            val DEFAULT = Rules()
        }
    }

    /** Country / region labels panels put in front of a channel name. Compared uppercase. */
    private val COUNTRY = setOf(
        // ISO 3166-1 alpha-2 that panels actually use as prefixes
        "AD", "AE", "AF", "AL", "AM", "AO", "AR", "AT", "AU", "AZ", "BA", "BD", "BE", "BG", "BH", "BO",
        "BR", "BY", "CA", "CH", "CL", "CN", "CO", "CR", "CY", "CZ", "DE", "DK", "DO", "DZ", "EC", "EE",
        "EG", "ES", "ET", "FI", "FR", "GB", "GE", "GH", "GR", "GT", "HK", "HN", "HR", "HU", "ID", "IE",
        "IL", "IN", "IQ", "IR", "IS", "IT", "JM", "JO", "JP", "KE", "KR", "KW", "KZ", "LB", "LK", "LT",
        "LU", "LV", "LY", "MA", "MD", "ME", "MK", "MT", "MX", "MY", "NG", "NL", "NO", "NP", "NZ", "OM",
        "PA", "PE", "PH", "PK", "PL", "PR", "PT", "PY", "QA", "RO", "RS", "RU", "SA", "SE", "SG", "SI",
        "SK", "SN", "SO", "SV", "SY", "TH", "TN", "TR", "TW", "UA", "UG", "UK", "US", "UY", "UZ", "VE",
        "VN", "YE", "ZA", "ZW",
        // alpha-3 and the labels IPTV panels invent
        "USA", "GBR", "IRE", "GER", "DEU", "FRA", "ITA", "ESP", "POR", "PRT", "NED", "NLD", "BEL", "POL",
        "TUR", "ROM", "ROU", "RUS", "GRE", "ALB", "BUL", "BGR", "SRB", "CRO", "HRV", "BIH", "SVN", "SWE",
        "NOR", "DEN", "DNK", "FIN", "CAN", "AUS", "MEX", "BRA", "ARG", "IND", "PAK", "BAN", "AFG", "ARA",
        "ARB", "ARAB", "AFR", "LAT", "LATAM", "LATINO", "EXYU", "EX-YU", "YU", "KUR", "SOM", "ETH",
        "CARIB", "INT", "EU", "MENA", "ASIA", "NEPAL",
    )

    /** Quality / codec tags. Compared lowercase. "HD+" is NOT here — it is a German platform. */
    private val QUALITY = setOf(
        "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hdr", "hevc", "h265", "h.265", "h264",
        "h.264", "x265", "x264", "1080p", "1080i", "720p", "576p", "540p", "480p", "360p", "2160p",
        "50fps", "60fps", "raw",
    )

    /** Bracketed annotations that describe the FEED, not the channel. Compared lowercase. */
    private val BRACKET_TAGS = setOf(
        "not 24/7", "geo-blocked", "geo blocked", "geoblocked", "backup", "back up", "b/u", "alt",
        "multi-audio", "multi audio", "multi-sub", "multi sub", "vip", "live", "test",
    )

    /** Characters that separate a prefix from the name. */
    private const val SEPARATORS = ":|-–—•·»▎▏▍▌┃│║/\\_"

    private val OPEN_BRACKETS = "([{"
    private val CLOSE_BRACKETS = ")]}"

    fun clean(raw: String, rules: Rules = Rules.DEFAULT): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (rules.userTags.isNotEmpty()) s = stripUserTags(s, rules.userTags)
        var strippedPrefix = false
        if (rules.stripCountryPrefix) {
            val out = stripCountryPrefix(s)
            strippedPrefix = out.length != s.length
            s = out
        }
        if (rules.stripDecorations) s = stripDecorationChars(s)
        if (rules.stripQuality || rules.stripDecorations) s = stripBracketTags(s, rules)
        if (rules.stripQuality) {
            s = stripStyledTokens(s)
            s = stripTrailingQuality(s)
            if (strippedPrefix) s = stripLeadingQuality(s)
        }
        val tidy = tidy(s)
        return tidy.ifEmpty { raw.trim() }
    }

    /** Parses the user's tag list as typed in settings: comma / newline / semicolon separated. */
    fun parseTags(text: String?): List<String> =
        text.orEmpty().split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    // --- prefix ------------------------------------------------------------------------------

    /**
     * Strips ONE leading country label that is followed by a separator (or wrapped in brackets /
     * pipes), plus a quality segment glued to it (`UK | FHD | BBC One`). Up to two passes, for
     * `|EU| UK: BBC One`.
     */
    private fun stripCountryPrefix(input: String): String {
        var s = input
        repeat(2) {
            val next = stripOnePrefix(s) ?: return s
            s = next
        }
        return s
    }

    private fun stripOnePrefix(s: String): String? {
        var i = 0
        val n = s.length
        // optional opening wrapper: "[", "(", "|", "┃" …
        while (i < n && (s[i].isWhitespace() || s[i] in OPEN_BRACKETS || s[i] in SEPARATORS)) i++
        val start = i
        while (i < n && s[i].isLetter()) i++
        if (i == start) return null
        // "EX-YU" is the one hyphenated label in use.
        if (i + 1 < n && s[i] == '-' && s.substring(start, i).equals("EX", ignoreCase = true)) {
            var e = i + 1
            while (e < n && s[e].isLetter()) e++
            if (s.substring(start, e).uppercase() in COUNTRY) i = e
        }
        if (!isCountryLabel(s.substring(start, i))) return null
        // A separator or closing bracket MUST follow the label: "UK: BBC One", "[UK] BBC One".
        var j = i
        while (j < n && s[j] == ' ') j++
        if (j >= n) return null
        val sepStart = j
        while (j < n && (s[j] in SEPARATORS || s[j] in CLOSE_BRACKETS)) j++
        if (j == sepStart) return null              // "AL JAZEERA" — no separator, not a prefix
        while (j < n && s[j].isWhitespace()) j++
        // optional quality segment followed by its own separator: "UK | FHD | BBC One"
        var k = j
        while (k < n && !s[k].isWhitespace() && s[k] !in SEPARATORS) k++
        if (k > j && s.substring(j, k).lowercase() in QUALITY) {
            var m = k
            while (m < n && s[m] == ' ') m++
            val q = m
            while (m < n && (s[m] in SEPARATORS || s[m] in CLOSE_BRACKETS)) m++
            if (m > q) {
                while (m < n && s[m].isWhitespace()) m++
                j = m
            }
        }
        val rest = s.substring(j)
        return if (rest.isBlank()) null else rest
    }

    /** `UK`, `usa`, and the glued `UKHD` / `DEFHD` shapes (country + quality, no gap). */
    private fun isCountryLabel(label: String): Boolean {
        val up = label.uppercase()
        if (up in COUNTRY) return true
        for (q in GLUED_QUALITY) {
            if (up.length > q.length && up.endsWith(q) && up.dropLast(q.length) in COUNTRY) return true
        }
        return false
    }

    private val GLUED_QUALITY = listOf("FHD", "UHD", "HD", "SD", "4K")

    // --- decorations -------------------------------------------------------------------------

    /** Emoji, dingbats, box/geometric symbols → space. `+` (a math symbol) and letters survive. */
    private fun stripDecorationChars(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                // Astral plane: emoji/pictographs (U+1F000..U+1FAFF) are decoration; keep the rest.
                val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
                if (cp in 0x1F000..0x1FAFF) sb.append(' ') else sb.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (isDecoration(c)) sb.append(' ') else sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun isDecoration(c: Char): Boolean {
        val code = c.code
        return code in 0x2190..0x21FF ||   // arrows
            code in 0x2500..0x25FF ||      // box drawing, blocks, geometric shapes (┃ ▎ ● ◉ ■ ▶)
            code in 0x2600..0x27BF ||      // misc symbols + dingbats (★ ☆ ✪ ✯ ❖ ⚽)
            code in 0x2B00..0x2BFF ||      // misc symbols and arrows (⭐)
            code == 0xFE0F || code == 0x200D || // emoji variation selector / zero-width joiner
            code == 0x00AE || code == 0x2122 || code == 0x00A9 // ® ™ ©
    }

    /**
     * Removes `(…)` / `[…]` / `{…}` groups that describe the feed: a resolution, a quality tag,
     * or a known annotation such as `[Not 24/7]`. Any other bracket — `(East)`, `(Pacific)` — is
     * part of the channel's identity and stays.
     */
    private fun stripBracketTags(s: String, rules: Rules): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val open = OPEN_BRACKETS.indexOf(s[i])
            if (open >= 0) {
                val close = s.indexOf(CLOSE_BRACKETS[open], i + 1)
                if (close > i) {
                    val inner = s.substring(i + 1, close).trim().lowercase()
                    val drop = (rules.stripQuality && (inner in QUALITY || isResolution(inner))) ||
                        (rules.stripDecorations && inner in BRACKET_TAGS)
                    if (drop) {
                        sb.append(' ')
                        i = close + 1
                        continue
                    }
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    private fun isResolution(t: String): Boolean {
        if (t.length < 4 || t.length > 5) return false
        val last = t.last()
        return (last == 'p' || last == 'i') && t.dropLast(1).all { it.isDigit() }
    }

    /**
     * A whitespace token written entirely in superscript / modifier / small-capital letters —
     * `ᴴᴰ`, `ᶠᴴᴰ`, `ᵁᴴᴰ`, `⁴ᴷ`, `ʀᴀᴡ`. Real channel names never use these; panels use them as
     * stylised quality badges.
     */
    private fun stripStyledTokens(s: String): String =
        s.split(' ').filter { tok -> tok.isEmpty() || !tok.all { isStyledLetter(it) } }.joinToString(" ")

    private fun isStyledLetter(c: Char): Boolean {
        val code = c.code
        return code in 0x1D00..0x1DBF ||     // phonetic extensions (small caps ᴀ, modifiers ᴴ ᴰ ᵁ)
            code in 0x02B0..0x02FF ||        // spacing modifier letters (ʰ ˢ)
            code in 0x2070..0x209F ||        // superscripts/subscripts (⁴ ⁺)
            code in 0x0250..0x02AF           // IPA extensions, used for small caps (ʀ ɪ ʟ)
    }

    /**
     * Drops quality tokens from the end. Tokens with no letter or digit (a kept `★`, a dangling
     * `-`) are stepped over, not treated as the end of the name; the first real word stops it.
     */
    private fun stripTrailingQuality(s: String): String {
        val toks = s.trim().split(' ').filter { it.isNotEmpty() }.toMutableList()
        var i = toks.lastIndex
        while (i > 0) {
            val t = toks[i]
            when {
                t.none { it.isLetterOrDigit() } -> i--
                isQualityToken(t) && toks.count { tok -> tok.any { it.isLetterOrDigit() } } > 1 -> { toks.removeAt(i); i-- }
                else -> break
            }
        }
        return toks.joinToString(" ")
    }

    private fun stripLeadingQuality(s: String): String {
        val toks = s.trim().split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (toks.size > 1 && isQualityToken(toks.first())) toks.removeAt(0)
        return toks.joinToString(" ")
    }

    /** `HD`, `-HD`, `FHD`, `(HD)`-free forms; a trailing separator on the token is ignored. */
    private fun isQualityToken(tok: String): Boolean {
        val t = tok.trim { it in SEPARATORS }.lowercase()
        return t.isNotEmpty() && t in QUALITY
    }

    // --- user tags ---------------------------------------------------------------------------

    private fun stripUserTags(input: String, tags: List<String>): String {
        var s = input
        for (raw in tags) {
            val tag = raw.trim()
            if (tag.isEmpty()) continue
            s = if (tag.all { it.isLetterOrDigit() }) removeWord(s, tag) else removeLiteral(s, tag)
        }
        return s
    }

    /** Case-insensitive removal of [word] where it stands as a whole word. */
    private fun removeWord(s: String, word: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s.regionMatches(i, word, 0, word.length, ignoreCase = true)) {
                val before = if (i == 0) ' ' else s[i - 1]
                val afterIdx = i + word.length
                val after = if (afterIdx >= s.length) ' ' else s[afterIdx]
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) {
                    sb.append(' ')
                    i = afterIdx
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    private fun removeLiteral(s: String, lit: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s.regionMatches(i, lit, 0, lit.length, ignoreCase = true)) {
                sb.append(' ')
                i += lit.length
                continue
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    // --- tidy --------------------------------------------------------------------------------

    /** Collapse whitespace, drop dangling separators at either end and empty bracket pairs. */
    private fun tidy(s: String): String {
        var out = s.replace("()", " ").replace("[]", " ").replace("{}", " ")
        val sb = StringBuilder(out.length)
        var lastSpace = false
        for (c in out) {
            if (c.isWhitespace()) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(c); lastSpace = false
            }
        }
        out = sb.toString().trim()
        // A trailing lone "-" / "|" left behind by a removed tag ("TOGGO plus -HD" → "TOGGO plus -").
        while (out.isNotEmpty() && (out.last() in SEPARATORS)) out = out.dropLast(1).trimEnd()
        while (out.isNotEmpty() && (out.first() in SEPARATORS)) out = out.drop(1).trimStart()
        return out
    }
}
