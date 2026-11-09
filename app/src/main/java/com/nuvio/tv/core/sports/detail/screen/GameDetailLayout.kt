package com.nuvio.tv.core.sports.detail.screen

/**
 * How the By Period card fits the height the left column leaves it (TV canvas is a fixed 960 x 540 dp, no scroll).
 * The win-chance card can push it down to ~70 dp, so it degrades in a fixed order and NEVER clips a team row:
 * 1. full (own title row, period header, roomy rows) → 2. title folded into the header row, rows shrink to fit →
 * 3. header dropped too → 4. card dropped (the scoreboard already shows the score).
 */
object ByPeriodFit {
    const val PADDING_DP = 20 // the card's vertical padding (10 + 10)
    const val TITLE_DP = 25
    const val HEADER_DP = 20
    const val ROW_IDEAL_DP = 34
    const val ROW_MIN_DP = 22

    data class Fit(val titleRow: Boolean, val header: Boolean, val rowDp: Int)

    fun heightOf(f: Fit, rows: Int): Int =
        PADDING_DP + (if (f.titleRow) TITLE_DP else 0) + (if (f.header) HEADER_DP else 0) + rows * f.rowDp

    fun layout(availableDp: Int, rows: Int): Fit? {
        if (rows <= 0) return null
        if (PADDING_DP + TITLE_DP + HEADER_DP + rows * ROW_IDEAL_DP <= availableDp) return Fit(true, true, ROW_IDEAL_DP)
        for (header in listOf(true, false)) {
            val room = availableDp - PADDING_DP - (if (header) HEADER_DP else 0)
            val row = minOf(ROW_IDEAL_DP, room / rows)
            if (row >= ROW_MIN_DP) return Fit(titleRow = false, header = header, rowDp = row)
        }
        return null
    }
}

/**
 * The words on the TV source row. The button is a short verb; the channel it opens is named in the line under it,
 * because provider channel titles ("NHL | 01 - 1pm Panthers @ Bruins") are far too long for a 10-foot button.
 */
object WatchCopy {
    fun primaryLabel(p: PrimaryAction): String? = when (p) {
        is PrimaryAction.Play -> "▶  Play"
        is PrimaryAction.Switch -> "⇄  Switch"
        PrimaryAction.AddProvider -> "Add IPTV provider"
        is PrimaryAction.Replay -> "↩  Watch replay"
        is PrimaryAction.Rematch -> if (p.failed) "Retry" else "Find channels"
        PrimaryAction.None -> null
    }

    fun statusLine(w: WatchUi): String = when (w.status) {
        WatchStatus.FINDING -> if (w.mode == WatchMode.WATCHING && w.currentChannelName != null) "Watching ${w.currentChannelName} · finding channels…" else "Finding channels…"
        WatchStatus.READY -> when (val p = w.primary) {
            is PrimaryAction.Replay -> "Replay from kick-off on ${p.channel.name}"
            is PrimaryAction.Switch -> "Watching ${w.currentChannelName ?: "a channel"} · switch to ${p.channel.name}"
            is PrimaryAction.Play -> "On ${p.channel.name}"
            else -> if (w.mode == WatchMode.WATCHING) "Watching ${w.currentChannelName ?: "a channel"}" else "Found on your channels"
        }
        WatchStatus.ALREADY_ON_IT -> "Watching this game on ${w.currentChannelName ?: "this channel"}"
        WatchStatus.NO_CHANNELS -> "None of your channels list this game yet. Find channels searches your playlists."
        WatchStatus.HIDDEN -> ""
        WatchStatus.FAILED -> "Couldn't check your channels"
        WatchStatus.NO_PROVIDER -> "Add an IPTV playlist to watch this game"
    }
}
