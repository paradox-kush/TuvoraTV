package com.nuvio.tv.core.iptv.epg

import com.nuvio.tv.core.iptv.content.EpgCensusRow

/**
 * B10 — the playlist screen's one-line answer to "how much of my lineup has a guide?", from the last
 * ingest's census. The denominator is the ELIGIBLE lineup (24/7 loops, PPV, dated event feeds and
 * separator rows can never have a guide), which is what a TiviMate comparison is really about.
 * Pure; the KMP twin renders the same words.
 */
object GuideCensusText {

    fun line(c: EpgCensusRow, picks: Int): String {
        val matched = minOf(c.matched, c.lineup)
        val parts = buildList {
            if (c.byId > 0) add("${group(c.byId)} by provider id")
            if (c.byName > 0) add("${group(c.byName)} by name")
            if (c.fuzzy > 0) add("${group(c.fuzzy)} by close spelling")
            if (picks > 0) add("${group(picks)} picked by you")
        }
        val base = if (c.eligible > 0) {
            "Playlist guide: ${group(minOf(matched, c.eligible))} of ${group(c.eligible)} channels matched"
        } else {
            "Playlist guide: ${group(matched)} of ${group(c.lineup)} channels matched"
        }
        val detail = if (parts.isEmpty()) "." else " (" + parts.joinToString(" · ") + ")."
        val noGuide = c.lineup - c.eligible
        val tail = buildString {
            if (noGuide > 0) append(" ${group(noGuide)} more are 24/7, PPV or event channels without a guide.")
            if (c.sourcesFailed > 0) append(" ${c.sourcesFailed} of ${c.sources} guide sources failed to download.")
        }
        return base + detail + tail
    }

    /** 12345 -> "12,345" (common code has no locale formatter). */
    internal fun group(n: Int): String {
        val s = n.toString()
        val neg = s.startsWith("-")
        val digits = if (neg) s.drop(1) else s
        val out = StringBuilder()
        digits.reversed().forEachIndexed { i, ch -> if (i > 0 && i % 3 == 0) out.append(','); out.append(ch) }
        return (if (neg) "-" else "") + out.reverse().toString()
    }
}
