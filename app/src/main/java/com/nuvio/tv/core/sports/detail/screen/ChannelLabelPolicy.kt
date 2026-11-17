package com.nuvio.tv.core.sports.detail.screen

/**
 * A provider's channel name as a viewer should read it: "UK || SKY SPORTS MAIN EVENT" -> "Sky Sports Main Event".
 *  - a leading country/provider tag goes: `UK ||`, `US |`, `|UK|`, `[UK]`, `(DE)`, `USA:` (a short token of letters/digits followed by a
 *    pipe, colon or bracket; a hyphen is never a separator, so "A-League TV" is safe);
 *  - trailing quality tags go: HD, FHD, UHD, SD, 4K, 8K, RAW, 50/60 FPS, HEVC, H265, and a trailing [..] / (..);
 *  - an ALL-CAPS name is title-cased, keeping known acronyms and digit tokens ("F1", "ESPN 2") upper; mixed case is left as the provider wrote it;
 *  - cleaning that leaves nothing returns the trimmed raw text.
 * Pure: no I/O, no locale.
 */
object ChannelLabelPolicy {
    private val prefix = Regex("""^\s*(?:[\[(]\s*[A-Za-z0-9]{1,5}\s*[\])]|\|{1,2}\s*[A-Za-z0-9]{1,5}\s*\|{1,2}|[A-Za-z0-9]{1,5}\s*(?:\|{1,2}|:))\s*""")
    private val trailingBracket = Regex("""\s*[\[(][^\])]*[\])]\s*$""")
    private val trailingTag = Regex("""\s+(?:HD|FHD|UHD|SD|4K|8K|RAW|HEVC|H\.?265|H\.?264|\d{2}\s?FPS)\s*$""", RegexOption.IGNORE_CASE)
    private val keepUpper = setOf(
        "ESPN", "NFL", "NBA", "NHL", "MLB", "UFC", "WWE", "TNT", "BBC", "ITV", "BT", "DAZN", "HBO", "TSN", "NBC", "CBS", "ABC", "FOX",
        "CNN", "PGA", "MLS", "EPL", "TV", "UK", "US", "USA", "VIP", "PPV", "RTE", "RTL", "SBS", "NPO", "SVT", "TVP", "AMC", "FX", "MTV",
    )

    fun clean(raw: String): String {
        val trimmed = raw.trim()
        var s = trimmed
        s = prefix.replace(s, "")
        while (true) {
            val next = trailingTag.replace(trailingBracket.replace(s, ""), "")
            if (next == s) break
            s = next
        }
        s = s.replace(Regex("""\s+"""), " ").trim()
        if (s.isEmpty() || s.none { it.isLetter() }) return trimmed
        return if (isAllCaps(s)) s.split(' ').joinToString(" ") { titleWord(it) } else s
    }

    private fun isAllCaps(s: String): Boolean {
        val letters = s.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.all { it.isUpperCase() }
    }

    private fun titleWord(w: String): String {
        if (w.any { it.isDigit() } || w.uppercase() in keepUpper) return w
        return w.lowercase().replaceFirstChar { it.uppercase() }
    }
}
