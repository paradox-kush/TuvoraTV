package com.nuvio.tv.core.radar.alerts

/**
 * The one decision point for "show, hold or drop this alert". Pure: no clock, no network, no Android. Twin of the
 * Mobile/Desktop `SportsAlertPolicy`; the eight gates and their order are the contract (see
 * research/sports-alerts-spec-2026-11-24.md section 3).
 */
object SportsAlertPolicy {
    const val MAX_ALERT_AGE_MS = 5 * 60 * 1000L
    const val SEEN_KEYS_CAP = 200
    const val CAP_WINDOW_MS = 60 * 60 * 1000L
    const val SUMMARY_MAX_LINES = 3

    /** Sports whose score alert reads "Goal". Every other sport reads "Score". */
    private val GOAL_SPORTS = setOf("soccer", "ice_hockey")

    fun onAlert(
        alert: SportsAlert,
        settings: SportsAlertSettings,
        ctx: AlertContext,
        ledger: AlertLedger,
    ): Pair<AlertDecision, AlertLedger> {
        // Gate 1: disabled, duplicate, stale.
        if (!settings.enabled) return reject(DropReason.Disabled, ledger)
        if (alert.key in ledger.seenKeys) return reject(DropReason.Duplicate, ledger)
        val seen = ledger.copy(seenKeys = (ledger.seenKeys + alert.key).trimmedToCap())
        if (ctx.nowMs - alert.atMs > MAX_ALERT_AGE_MS) return reject(DropReason.Stale, seen)

        // Gate 2: follows, mutes, level.
        gate2(alert, settings, ctx)?.let { return reject(it, seen) }
        // Gates 3-5: watching, quiet hours, during playback.
        gateRelease(alert, settings, ctx)?.let { return reject(it, seen) }

        // Gate 6: spoilers.
        val hidden = scoresHidden(alert, ctx)
        if (hidden && alert.kind == AlertKind.Score) return reject(DropReason.ScoresHidden, seen)
        val delayMs = if (hidden) 0L else ctx.spoiler.delayMs
        if (delayMs > 0 && alert.kind != AlertKind.Start) {
            val due = ctx.nowMs + delayMs
            return AlertDecision.HoldUntil(due, HoldReason.Delay) to
                seen.copy(pending = seen.pending + Held(alert, due, HoldReason.Delay))
        }
        return admit(alert, settings, ctx, seen)
    }

    /** Flushes whatever is due: delayed alerts re-run gates 3-5, batched alerts become one banner (or a summary). */
    fun onTimer(
        settings: SportsAlertSettings,
        ctx: AlertContext,
        ledger: AlertLedger,
    ): Pair<AlertBanner?, AlertLedger> {
        val due = ledger.pending.filter { it.dueAtMs <= ctx.nowMs }
        if (due.isEmpty()) return null to ledger
        var rest = ledger.pending - due.toSet()
        val ready = mutableListOf<SportsAlert>()
        due.forEach { held ->
            if (!settings.enabled) return@forEach
            val alert = held.alert
            if (gateRelease(alert, settings, ctx) != null) return@forEach
            // A game hidden since it was held loses its score alerts; a hidden final loses its numbers at render.
            if (alert.kind == AlertKind.Score && scoresHidden(alert, ctx)) return@forEach
            if (held.reason == HoldReason.Delay) {
                // Delay elapsed: now only the batch window can still hold it.
                val batchDue = batchDueAt(settings, ctx, ledger.copy(pending = rest))
                if (batchDue != null) {
                    rest = rest + Held(alert, batchDue, HoldReason.Batch)
                    return@forEach
                }
            }
            ready += alert
        }
        var next = ledger.copy(pending = rest)
        if (ready.isEmpty()) return null to next
        if (capReached(settings, ctx, next)) return null to next
        val banner = banner(ready, ctx)
        next = next.copy(
            shownAtMs = recentShown(next, ctx.nowMs) + ctx.nowMs,
            lastBannerAtMs = ctx.nowMs,
        )
        return banner to next
    }

    /** When the one-shot local timer should fire next, or null when nothing is held. No network. */
    fun nextTimerAtMs(ledger: AlertLedger): Long? = ledger.pending.minOfOrNull { it.dueAtMs }

    // ---- gates ----

    private fun gate2(alert: SportsAlert, settings: SportsAlertSettings, ctx: AlertContext): DropReason? {
        val followedTeams = listOfNotNull(alert.homeId, alert.awayId).filter { it in ctx.followedTeamIds }
        val leagueFollowed = alert.leagueId != null && alert.leagueId in ctx.followedLeagueIds
        if (followedTeams.isEmpty() && !leagueFollowed) return DropReason.NotFollowed
        val override = settings.gameOverride(alert.eventId, ctx.nowMs)
        if (override?.isOff == true) return DropReason.Muted
        val teamActive = followedTeams.any { it !in settings.mutedTeams }
        val leagueActive = leagueFollowed && alert.leagueId !in settings.mutedLeagues
        if (!teamActive && !leagueActive) return DropReason.Muted
        val level = when {
            override?.level == GameAlertOverride.LEVEL_ALL -> AlertLevel.All
            teamActive -> settings.teamLevel
            else -> settings.leagueLevel
        }
        return if (level.includes(alert.kind)) null else DropReason.KindOff
    }

    /** Gates 3-5. Re-run when a held alert is released: the viewer may have opened the game or started a film meanwhile. */
    private fun gateRelease(alert: SportsAlert, settings: SportsAlertSettings, ctx: AlertContext): DropReason? {
        if (ctx.openGameEventId == alert.eventId || ctx.watchingEventId == alert.eventId) return DropReason.WatchingThisGame
        if (inQuietHours(settings, ctx.localMinuteOfDay)) return DropReason.QuietHours
        val blocked = when (settings.duringPlayback) {
            DuringPlayback.Always -> false
            DuringPlayback.Never -> ctx.playback != PlaybackContext.None
            DuringPlayback.LiveTvOnly -> ctx.playback == PlaybackContext.Vod
        }
        return if (blocked) DropReason.DuringPlayback else null
    }

    /** Gates 7 and 8 for an alert that has cleared everything else. */
    private fun admit(
        alert: SportsAlert,
        settings: SportsAlertSettings,
        ctx: AlertContext,
        ledger: AlertLedger,
    ): Pair<AlertDecision, AlertLedger> {
        if (capReached(settings, ctx, ledger)) return reject(DropReason.HourlyCap, ledger)
        val batchDue = batchDueAt(settings, ctx, ledger)
        if (batchDue != null) {
            return AlertDecision.HoldUntil(batchDue, HoldReason.Batch) to
                ledger.copy(pending = ledger.pending + Held(alert, batchDue, HoldReason.Batch))
        }
        val banner = banner(listOf(alert), ctx)
        return AlertDecision.ShowNow(banner) to ledger.copy(
            shownAtMs = recentShown(ledger, ctx.nowMs) + ctx.nowMs,
            lastBannerAtMs = ctx.nowMs,
        )
    }

    private fun capReached(settings: SportsAlertSettings, ctx: AlertContext, ledger: AlertLedger): Boolean =
        settings.maxPerHour > 0 && recentShown(ledger, ctx.nowMs).size >= settings.maxPerHour

    private fun recentShown(ledger: AlertLedger, nowMs: Long): List<Long> =
        ledger.shownAtMs.filter { nowMs - it < CAP_WINDOW_MS }

    /** Leading-edge batching: null = show now; else the time the batch flushes. Already-held batch alerts join their flush. */
    private fun batchDueAt(settings: SportsAlertSettings, ctx: AlertContext, ledger: AlertLedger): Long? {
        if (settings.batchSeconds <= 0) return null
        val queued = ledger.pending.filter { it.reason == HoldReason.Batch }.minOfOrNull { it.dueAtMs }
        if (queued != null) return queued
        val last = ledger.lastBannerAtMs ?: return null
        val due = last + settings.batchMs
        return if (ctx.nowMs < due) due else null
    }

    fun inQuietHours(settings: SportsAlertSettings, minuteOfDay: Int): Boolean {
        if (!settings.quietOn) return false
        val start = settings.quietStartMin
        val end = settings.quietEndMin
        if (start == end) return false
        return if (start < end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end
    }

    /** The game's numbers are hidden: the profile hides scores and this game was not revealed for the session. */
    private fun scoresHidden(alert: SportsAlert, ctx: AlertContext): Boolean =
        !ctx.spoiler.shown && alert.eventId !in ctx.revealed

    // ---- text ----

    private fun banner(alerts: List<SportsAlert>, ctx: AlertContext): AlertBanner {
        val perGame = alerts.groupBy { it.eventId }.values.map { group ->
            // Per game keep only the newest alert; a final beats a score.
            group.withIndex().maxWith(
                compareBy<IndexedValue<SportsAlert>> { it.value.kind == AlertKind.Final }
                    .thenBy { it.value.atMs }
                    .thenBy { it.index },
            ).value
        }
        if (perGame.size == 1) return single(perGame.single(), ctx)
        val lines = perGame.take(SUMMARY_MAX_LINES).map { line(it, ctx) }
        return AlertBanner(title = "${perGame.size} sports updates", detail = null, lines = lines, eventId = null, size = perGame.size)
    }

    private fun single(alert: SportsAlert, ctx: AlertContext): AlertBanner = AlertBanner(
        title = line(alert, ctx),
        detail = listOfNotNull(alert.league, alert.progress).joinToString(" · ").ifBlank { null },
        eventId = alert.eventId,
        kind = alert.kind,
        sport = alert.sport,
    )

    /** One line of text for an alert, numbers only when the policy lets them show. */
    fun line(alert: SportsAlert, ctx: AlertContext): String {
        val teams = "${alert.home} v ${alert.away}"
        val withScore = if (alert.homeScore != null && alert.awayScore != null && !scoresHidden(alert, ctx)) {
            "${alert.home} ${alert.homeScore}–${alert.awayScore} ${alert.away}"
        } else teams
        return when (alert.kind) {
            AlertKind.Start -> "$teams has started"
            AlertKind.Score -> "${scoreWord(alert)} · $withScore"
            AlertKind.Final -> "Full time · $withScore"
        }
    }

    private fun scoreWord(alert: SportsAlert): String = when {
        alert.corrected -> "Score corrected"
        alert.sport.lowercase().replace(' ', '_') in GOAL_SPORTS -> "Goal"
        else -> "Score"
    }

    private fun reject(reason: DropReason, ledger: AlertLedger): Pair<AlertDecision, AlertLedger> =
        AlertDecision.Drop(reason) to ledger

    private fun Set<String>.trimmedToCap(): Set<String> =
        if (size <= SEEN_KEYS_CAP) this else drop(size - SEEN_KEYS_CAP).toCollection(LinkedHashSet())
}
