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

/**
 * How many rows the right-column section panel can show (TV canvas is a fixed 960 x 540 dp, no scroll, nothing hard-clipped).
 * The spec caps (Plays 9, Stats 6 + by-period, Leaders 4 + form, Standings 7; with a channel preview 4 / 3 / 2 / 3) are the MOST a
 * panel shows; [rows] shrinks them to what the measured height really holds, so a short panel drops rows instead of clipping one.
 */
object PanelFit {
    /** The right column's height (540 - 28 top - 40 bottom). */
    const val COLUMN_DP = 472
    const val GAP_DP = 12
    const val PREVIEW_DP = 160
    const val CHIPS_DP = 40
    const val CARD_PADDING_DP = 20
    const val TITLE_DP = 25

    const val ROW_PLAYS_DP = 40
    const val ROW_STATS_DP = 34
    const val ROW_LEADERS_DP = 36
    const val ROW_STANDINGS_DP = 30
    const val STANDINGS_HEADER_DP = 22
    const val BY_PERIOD_EXTRA_DP = 100
    const val FORM_EXTRA_DP = 100

    data class Panel(val rows: Int, val extra: Boolean)

    /** Height the panel card gets in the right column. */
    fun panelHeightDp(hasPreview: Boolean): Int =
        COLUMN_DP - CHIPS_DP - GAP_DP - (if (hasPreview) PREVIEW_DP + GAP_DP else 0)

    fun cap(section: GameSection, hasPreview: Boolean): Int = when (section) {
        GameSection.PLAYS -> if (hasPreview) 4 else 9
        GameSection.STATS -> if (hasPreview) 3 else 6
        GameSection.LEADERS -> if (hasPreview) 2 else 4
        GameSection.STANDINGS -> if (hasPreview) 3 else 7
        GameSection.LINEUPS -> if (hasPreview) 4 else 9
        GameSection.OVERVIEW -> 0
    }

    private fun rowDp(section: GameSection) = when (section) {
        GameSection.PLAYS, GameSection.LINEUPS -> ROW_PLAYS_DP
        GameSection.STATS -> ROW_STATS_DP
        GameSection.LEADERS -> ROW_LEADERS_DP
        GameSection.STANDINGS -> ROW_STANDINGS_DP
        GameSection.OVERVIEW -> Int.MAX_VALUE
    }

    private fun overheadDp(section: GameSection) =
        CARD_PADDING_DP + TITLE_DP + (if (section == GameSection.STANDINGS) STANDINGS_HEADER_DP else 0)

    /** Rows for [section] in [availableDp] (>= 0, never above the spec cap, never more than [available] rows exist). */
    fun rows(section: GameSection, availableDp: Int, hasPreview: Boolean, available: Int = Int.MAX_VALUE): Int {
        val fit = ((availableDp - overheadDp(section)) / rowDp(section)).coerceAtLeast(0)
        return minOf(cap(section, hasPreview), fit, available)
    }

    /** The panel of [section] for the measured height: row count plus whether the extra compartment (by-period under Stats, form under Leaders) fits. */
    fun fit(section: GameSection, availableDp: Int, hasPreview: Boolean, available: Int = Int.MAX_VALUE, hasExtra: Boolean = false): Panel {
        val n = rows(section, availableDp, hasPreview, available)
        val extraDp = when (section) { GameSection.STATS -> BY_PERIOD_EXTRA_DP; GameSection.LEADERS -> FORM_EXTRA_DP; else -> Int.MAX_VALUE }
        val used = overheadDp(section) + n * rowDp(section)
        return Panel(n, hasExtra && !hasPreview && availableDp - used >= extraDp)
    }
}

/**
 * The LEFT column (scoreboard, strip, last-play line, win-chance card [+ chart], source row) on the fixed 472 dp canvas. When it cannot
 * fit, the win-chance CHART is the first thing to drop, then the last-play line (spec 03 B3), then the win-chance card compacts and the
 * gaps tighten; the scoreboard, the strip, the win-chance numbers and the source row are never dropped. Heights are measured on the 960 x 540 dp canvas (1080p emulator frames).
 */
object LeftColumnFit {
    enum class Strip { NONE, FOOTBALL, SOCCER, BASKETBALL, HOCKEY }

    data class Parts(
        val strip: Strip = Strip.NONE,
        val lastPlay: Boolean,
        val winChance: Boolean,
        val chart: Boolean,
        val source: Boolean,
        /** Extra scoreboard height (soccer scorer lines under the team names). */
        val scorerLines: Int = 0,
    )

    /**
     * [compactWinChance]: the win-chance card shrinks to a numbers row + bar + the one-line credit (the Polymarket credit is never dropped; the chart and the extra line are); [tightGaps]: cards sit [TIGHT_GAP_DP]
     * apart. Both are the last resorts once the chart and the last-play line are gone, so the minimum column is <= the 472 dp canvas
     * for every sport (the Play row is never squeezed).
     */
    data class Plan(
        val showChart: Boolean,
        val showLastPlay: Boolean,
        val compactWinChance: Boolean = false,
        val tightGaps: Boolean = false,
    )

    const val GAP_DP = 12
    const val TIGHT_GAP_DP = 6
    const val COMPACT_WIN_CHANCE_DP = 66
    const val SCOREBOARD_DP = 181
    const val SCORER_LINE_DP = 15
    const val FOOTBALL_STRIP_DP = 104
    const val SOCCER_STRIP_DP = 92
    const val BASKETBALL_STRIP_DP = 100
    const val HOCKEY_STRIP_DP = 88
    const val LAST_PLAY_DP = 38
    const val WIN_CHANCE_DP = 100
    const val CHART_DP = 76
    const val SOURCE_DP = 83

    fun heightOf(p: Parts, plan: Plan): Int = heightOf(p, plan.showChart, plan.showLastPlay, plan.compactWinChance, plan.tightGaps)

    fun heightOf(p: Parts, showChart: Boolean, showLastPlay: Boolean, compactWinChance: Boolean = false, tightGaps: Boolean = false): Int {
        val cards = buildList {
            add(SCOREBOARD_DP + p.scorerLines * SCORER_LINE_DP)
            when (p.strip) { Strip.FOOTBALL -> add(FOOTBALL_STRIP_DP); Strip.SOCCER -> add(SOCCER_STRIP_DP)
                Strip.BASKETBALL -> add(BASKETBALL_STRIP_DP); Strip.HOCKEY -> add(HOCKEY_STRIP_DP); Strip.NONE -> Unit }
            if (p.lastPlay && showLastPlay) add(LAST_PLAY_DP)
            if (p.winChance) add(if (compactWinChance) COMPACT_WIN_CHANCE_DP else WIN_CHANCE_DP + if (p.chart && showChart) CHART_DP else 0)
            if (p.source) add(SOURCE_DP)
        }
        return cards.sum() + (if (tightGaps) TIGHT_GAP_DP else GAP_DP) * (cards.size - 1).coerceAtLeast(0)
    }

    fun plan(availableDp: Int, p: Parts): Plan {
        var chart = p.chart
        var lastPlay = p.lastPlay
        if (heightOf(p, chart, lastPlay) > availableDp) chart = false
        if (heightOf(p, chart, lastPlay) > availableDp) lastPlay = false
        var compact = false
        var tight = false
        if (heightOf(p, chart, lastPlay) > availableDp) compact = true
        if (heightOf(p, chart, lastPlay, compact) > availableDp) tight = true
        return Plan(showChart = chart, showLastPlay = lastPlay, compactWinChance = compact, tightGaps = tight)
    }
}
