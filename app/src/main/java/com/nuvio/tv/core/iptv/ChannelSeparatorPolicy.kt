package com.nuvio.tv.core.iptv

/**
 * B179: providers pad a lineup with dummy "header" rows — `BG: ################ Bulgaria Channels`,
 * `*** UK ***`, `—— SPORT ——` — that exist only to label the group around them. They are not streams:
 * tuning one fails ("This device cannot play this stream").
 *
 * Pure and deliberately CONSERVATIVE: hiding a real channel is far worse than showing a divider, and
 * providers ship REAL placeholder channels that look decorated (`NBA 01: ------`, `ESPN+ 01 : ----`,
 * `PPV 3 | -----`, `EVENT 05 ━━`, `UK: Sky Sports ★★★`, `PPV: ****`, `📺📺📺`). So a name is a separator
 * only in one of three unmistakable shapes:
 *  1. decoration only, NO prefix: nothing but [DECOR_ONLY_MIN]+ decoration characters (`######`, `━━━━━━━━`);
 *  2. a long leading rule: [LONG_RUN]+ decoration characters, then words (`################ Bulgaria Channels`),
 *     after an optional `XX:` / `[XX]` prefix;
 *  3. a frame: a decoration run of [FRAME_RUN]+ ([LINE_FRAME_RUN]+ for rules) at BOTH ends around words
 *     (`*** UK ***`, `—— SPORT ——`), after the same optional prefix.
 * A prefix followed only by decoration (`PPV: ------`) is an event slot, a banner containing a digit
 * (`*** DAZN 1 ***`, `━━━━━━━━ UFC 300`) is a numbered channel, and a trailing-only or short run never
 * matches. Accepted under-filtering: `BG: ----------` stays listed. A row that
 * carries an EPG id or a catch-up archive is a real channel whatever it is called.
 *
 * This is a READ-time decision (what the hub, guide, search and Sports list). Nothing is deleted from
 * storage, so a favourite or saved id pointing at a row keeps resolving. Twin: NuvioMobile
 * `features/iptv/ChannelSeparatorPolicy.kt` (same rules and tests).
 */
object ChannelSeparatorPolicy {

    private const val ASCII_DECOR = "#=*~_-+"
    /** Rules and dashes. */
    private const val LINE_DECOR = "─━═▬—–"
    /** Block shapes. Emoji are NOT decoration: `📺📺📺` is a name. */
    private const val SHAPE_DECOR = "▀▄■□▪▫●○◆◇★☆•·"
    private const val DECOR_ONLY_MIN = 6
    private const val LONG_RUN = 8
    private const val FRAME_RUN = 3
    private const val LINE_FRAME_RUN = 2

    /** `BG:`, `UK |`, `[BG]` — the country/label prefix a provider puts before the banner. */
    private val PREFIX = Regex("""^\s*(?:\[[A-Za-z]{2,4}\]\s*|[A-Za-z]{2,4}\s*[:|]\s*)""")

    fun isSeparator(name: String, epgChannelId: String? = null, hasArchive: Boolean = false): Boolean {
        if (!epgChannelId.isNullOrBlank() || hasArchive) return false
        val text = name.trim()
        if (text.isEmpty()) return false
        // Decoration alone is a rule line — but ONLY with no prefix: `PPV: ------` / `UFC | ******` / `US: ++++++`
        // are event slots a provider fills in later, so a prefix followed by decoration is a channel.
        if (isDecorOnly(text)) return true
        val body = PREFIX.find(text)?.let { text.substring(it.range.last + 1).trim() } ?: text
        return isBanner(body)
    }

    /** Words between decoration: a long leading rule, or a frame. A digit anywhere means a numbered channel. */
    private fun isBanner(body: String): Boolean {
        if (body.isEmpty() || body.any { it.isDigit() } || body.none { it.isLetter() }) return false
        val lead = edgeRun(body, fromStart = true)
        if (lead.length >= LONG_RUN) return true
        return framesWords(lead) && framesWords(edgeRun(body, fromStart = false))
    }

    /** A frame edge: [FRAME_RUN] of anything, or [LINE_FRAME_RUN] when it is all rule/dash characters. */
    private fun framesWords(run: String): Boolean =
        run.length >= FRAME_RUN || (run.length >= LINE_FRAME_RUN && run.all { it in LINE_DECOR })

    private fun isDecor(c: Char) = c in ASCII_DECOR || c in LINE_DECOR || c in SHAPE_DECOR

    /** Every visible character is decoration (so emoji, letters and digits all disqualify). */
    private fun isDecorOnly(s: String): Boolean {
        var visible = 0
        for (c in s) {
            if (c.isWhitespace()) continue
            if (!isDecor(c)) return false
            visible++
        }
        return visible >= DECOR_ONLY_MIN
    }

    private fun edgeRun(s: String, fromStart: Boolean): String =
        (if (fromStart) s else s.reversed()).takeWhile { isDecor(it) }

    fun <T> withoutSeparators(
        rows: List<T>,
        name: (T) -> String,
        epgChannelId: (T) -> String?,
        hasArchive: (T) -> Boolean,
    ): List<T> = rows.filterNot { isSeparator(name(it), epgChannelId(it), hasArchive(it)) }
}
