package com.nuvio.tv.core.iptv

/**
 * B179: providers pad a lineup with dummy "header" rows — `BG: ############ Bulgaria Channels
 * ############`, `*** UK ***`, `—— SPORT ——` — that exist only to label the group above/below them.
 * They are not streams: tuning one fails ("This device cannot play this stream").
 *
 * Pure and deliberately CONSERVATIVE: a real channel hidden by mistake is far worse than a divider
 * shown, so a name is a separator only when it is plainly decoration — nothing but punctuation, or a
 * banner (a run of decoration characters at its start or end, after an optional `XX:` country
 * prefix). Single `#`/`-` inside a real name (`#0 Sport`, `E! HD`, `Sky Sports - 1`) never matches,
 * and a row that carries an EPG id or a catch-up archive is a real channel whatever it is called.
 *
 * Applied where lineups are written to the content DB and where Xtream lineups are read, so the hub,
 * guide, search and Sports matcher all see the same list and the counts agree. Twin: NuvioMobile
 * `features/iptv/ChannelSeparatorPolicy.kt` (same rules and tests).
 */
object ChannelSeparatorPolicy {

    /** Characters that, repeated, make a banner. ASCII needs [ASCII_RUN]; box/symbol art needs [SYMBOL_RUN]. */
    private const val ASCII_DECOR = "#=*~_-+"
    /** Rules and dashes: two in a row at the edge is already a banner (`—— SPORT ——`). */
    private const val LINE_DECOR = "─━═▬—–"
    /** Shapes: need three (`★★★ UK ★★★`), because one or two read as ordinary punctuation. */
    private const val SHAPE_DECOR = "▀▄■□▪▫●○◆◇★☆•·"
    private const val ASCII_RUN = 4
    private const val FRAME_RUN = 3
    private const val LINE_RUN = 2
    private const val SHAPE_RUN = 3
    /** Nothing but decoration needs at least this many visible characters (`--` alone is not enough). */
    private const val MIN_DECOR_ONLY = 3

    /** `BG:`, `UK |`, `DE -`, `[BG]` — the country/label prefix a provider puts before the banner. */
    private val PREFIX = Regex("""^\s*(?:\[[A-Za-z]{2,4}\]\s*|[A-Za-z]{2,4}\s*[:|]\s*)""")

    fun isSeparator(name: String, epgChannelId: String? = null, hasArchive: Boolean = false): Boolean {
        if (!epgChannelId.isNullOrBlank() || hasArchive) return false
        val text = name.trim()
        if (text.isEmpty()) return false
        if (isDecorOnly(text)) return true
        val body = PREFIX.find(text)?.let { text.substring(it.range.last + 1) }?.trim() ?: text
        if (body.isEmpty()) return false
        return isDecorOnly(body) || startsOrEndsWithBanner(body)
    }

    private fun isDecor(c: Char) = c in ASCII_DECOR || c in LINE_DECOR || c in SHAPE_DECOR

    /** Only decoration, spaces and stray punctuation: no letter or digit anywhere. */
    private fun isDecorOnly(s: String): Boolean {
        var visible = 0
        for (c in s) {
            if (c.isLetterOrDigit()) return false
            if (!c.isWhitespace()) visible++
        }
        return visible >= MIN_DECOR_ONLY
    }

    private fun startsOrEndsWithBanner(s: String): Boolean =
        hasBannerRun(s, fromStart = true) || hasBannerRun(s, fromStart = false) || framedByShortRuns(s)

    /** `*** UK ***`: a run of [FRAME_RUN] on BOTH ends is a frame even where one end alone would not be a banner. */
    private fun framedByShortRuns(s: String): Boolean =
        edgeDecor(s, fromStart = true) >= FRAME_RUN && edgeDecor(s, fromStart = false) >= FRAME_RUN && s.any { it.isLetterOrDigit() }

    private fun edgeDecor(s: String, fromStart: Boolean): Int =
        (if (fromStart) s else s.reversed()).takeWhile { isDecor(it) }.length

    private fun hasBannerRun(s: String, fromStart: Boolean): Boolean {
        val chars = if (fromStart) s else s.reversed()
        var run = 0
        var line = 0
        var shape = 0
        for (c in chars) {
            if (!isDecor(c)) break
            run++
            if (c in LINE_DECOR) line++ else if (c in SHAPE_DECOR) shape++
        }
        // A run of only lines/shapes is a banner at its (lower) threshold; ASCII needs ASCII_RUN, mixed or not.
        val symbolOnly = line + shape == run
        return run >= ASCII_RUN || (symbolOnly && (line >= LINE_RUN || shape >= SHAPE_RUN))
    }

    fun <T> withoutSeparators(
        rows: List<T>,
        name: (T) -> String,
        epgChannelId: (T) -> String?,
        hasArchive: (T) -> Boolean,
    ): List<T> = rows.filterNot { isSeparator(name(it), epgChannelId(it), hasArchive(it)) }
}
