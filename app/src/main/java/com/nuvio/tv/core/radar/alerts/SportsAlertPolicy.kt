package com.nuvio.tv.core.radar.alerts

/**
 * The one decision point for "show, hold or drop this alert". Pure: no clock, no network, no Android. Twin of the
 * Mobile/Desktop `SportsAlertPolicy`; the gates and their order are the contract (research/sports-alerts-spec-2026-11-24.md
 * section 3 plus the review-round-1 rulings).
 */
object SportsAlertPolicy {
    const val MAX_ALERT_AGE_MS = 5 * 60 * 1000L
    /** An `at` further ahead of the device clock than this is clock skew: age is then measured from receipt. */
    const val CLOCK_SKEW_MS = 60 * 1000L
    const val SEEN_KEYS_CAP = 200
    const val CAP_WINDOW_MS = 60 * 60 * 1000L
    const val SUMMARY_MAX_LINES = 3
    /** How long a Score/Final waits for the "is this the game I am watching?" lookup while live TV plays. */
    const val WATCH_CHECK_MS = 6_000L
    /** A hold released this much after its due time (a suspended process, a stalled timer) is stale. */
    const val OVERDUE_RELEASE_MS = 60_000L

    /** Sports whose score alert reads "Goal". Every other sport reads "Score". */
    private val GOAL_SPORTS = setOf("soccer", "ice_hockey")

    private sealed interface Gate {
        data object Pass : Gate
        data class Drop(val reason: DropReason) : Gate
        /** Live TV is playing and the watch state is unknown: hold for the lookup. */
        data object Check : Gate
    }

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
        if (ageMs(alert, ctx) > MAX_ALERT_AGE_MS) return reject(DropReason.Stale, seen)

        // Gate 2: follows, mutes, level.
        gate2(alert, settings, ctx)?.let { return reject(it, seen) }
        // Gates 3-5: watching, quiet hours, during playback.
        val hidden = scoresHidden(alert, ctx)
        when (val g = gateRelease(alert, settings, ctx)) {
            is Gate.Drop -> return reject(g.reason, seen)
            Gate.Check -> {
                if (hidden && alert.kind == AlertKind.Score) return reject(DropReason.ScoresHidden, seen)
                val due = ctx.nowMs + WATCH_CHECK_MS
                return AlertDecision.HoldUntil(due, HoldReason.WatchCheck) to
                    seen.copy(pending = seen.pending + Held(alert, due, HoldReason.WatchCheck, ctx.nowMs)).count("held:${HoldReason.WatchCheck.wire}")
            }
            Gate.Pass -> Unit
        }
        return afterWatch(alert, settings, ctx, seen, hidden)
    }

    /** Gate 6 onwards for an alert that cleared gates 1-5. */
    private fun afterWatch(
        alert: SportsAlert,
        settings: SportsAlertSettings,
        ctx: AlertContext,
        seen: AlertLedger,
        hidden: Boolean,
    ): Pair<AlertDecision, AlertLedger> {
        // Gate 6: spoilers.
        if (hidden && alert.kind == AlertKind.Score) return reject(DropReason.ScoresHidden, seen)
        val delayMs = if (hidden) 0L else ctx.spoiler.delayMs
        if (delayMs > 0 && alert.kind != AlertKind.Start) {
            val due = ctx.nowMs + delayMs
            return AlertDecision.HoldUntil(due, HoldReason.Delay) to
                seen.copy(pending = seen.pending + Held(alert, due, HoldReason.Delay, ctx.nowMs)).count("held:${HoldReason.Delay.wire}")
        }
        return admit(alert, settings, ctx, seen)
    }

    /**
     * Flushes whatever is due: delayed and watch-check alerts re-run gates 2-5, batched alerts become one banner (or a
     * summary). [resolvedEventIds] releases those games' watch-check holds early (their lookup answered).
     */
    fun onTimer(
        settings: SportsAlertSettings,
        ctx: AlertContext,
        ledger: AlertLedger,
        resolvedEventIds: Set<String> = emptySet(),
    ): Pair<AlertBanner?, AlertLedger> {
        val due = ledger.pending.filter {
            it.dueAtMs <= ctx.nowMs || (it.reason == HoldReason.WatchCheck && it.alert.eventId in resolvedEventIds)
        }
        if (due.isEmpty()) return null to ledger
        var rest = ledger.pending - due.toSet()
        var counts = ledger.counts
        fun dropped(reason: DropReason) {
            if (reason.wire in SportsAlertTelemetry.COUNT_ONLY_REASONS) counts = counts.bump("dropped:${reason.wire}")
        }
        val ready = mutableListOf<SportsAlert>()
        due.forEach { held ->
            if (!settings.enabled) return@forEach
            // A hold released far past its time, or older than a fresh alert may be (plus the score delay it was
            // waiting out), is stale: dropped uncounted, like any stale alert.
            if (ctx.nowMs - held.dueAtMs > OVERDUE_RELEASE_MS ||
                ctx.nowMs - held.receivedAtMs > MAX_ALERT_AGE_MS + ctx.spoiler.delayMs
            ) return@forEach
            var alert = held.alert
            // Follows and mutes may have changed while it waited.
            if (gate2(alert, settings, ctx) != null) return@forEach
            when (val g = gateRelease(alert, settings, ctx)) {
                is Gate.Drop -> { dropped(g.reason); return@forEach }
                Gate.Check -> if (held.reason == HoldReason.WatchCheck) {
                    // Still unknown at the end of the check: a score would spoil, a final is shown without numbers.
                    if (alert.kind == AlertKind.Score) { dropped(DropReason.WatchUnknown); return@forEach }
                    alert = alert.copy(homeScore = null, awayScore = null)
                } else {
                    // A delay / batch release while live TV plays an unknown channel: ask first, never show numbers
                    // on a guess. The check runs from now; the delay still counts from receipt.
                    rest = rest + Held(alert, ctx.nowMs + WATCH_CHECK_MS, HoldReason.WatchCheck, held.receivedAtMs)
                    counts = counts.bump("held:${HoldReason.WatchCheck.wire}")
                    return@forEach
                }
                Gate.Pass -> Unit
            }
            val hidden = scoresHidden(alert, ctx)
            // A game hidden since it was held loses its score alerts; a hidden final loses its numbers at render.
            if (alert.kind == AlertKind.Score && hidden) { dropped(DropReason.ScoresHidden); return@forEach }
            if (held.reason == HoldReason.WatchCheck) {
                // The score delay counts from RECEIPT, not from the check's release (a downgraded final included).
                val delayMs = if (hidden) 0L else ctx.spoiler.delayMs
                val delayDue = held.receivedAtMs + delayMs
                if (delayMs > 0 && alert.kind != AlertKind.Start && delayDue > ctx.nowMs) {
                    rest = rest + Held(alert, delayDue, HoldReason.Delay, held.receivedAtMs)
                    counts = counts.bump("held:${HoldReason.Delay.wire}")
                    return@forEach
                }
            }
            if (held.reason != HoldReason.Batch) {
                // Delay / watch check done: now only the batch window can still hold it.
                val batchDue = batchDueAt(settings, ctx, ledger.copy(pending = rest))
                if (batchDue != null) {
                    rest = rest + Held(alert, batchDue, HoldReason.Batch, held.receivedAtMs)
                    return@forEach
                }
            }
            ready += alert
        }
        var next = ledger.copy(pending = rest, counts = counts)
        if (ready.isEmpty()) return null to next
        if (capReached(settings, ctx, next)) {
            return null to next.copy(counts = next.counts.bump("dropped:${DropReason.HourlyCap.wire}", ready.size))
        }
        val banner = banner(ready, ctx)
        val superseded = ready.size - ready.map { it.eventId }.distinct().size
        if (superseded > 0) next = next.copy(counts = next.counts.bump("dropped:superseded", superseded))
        next = next.copy(
            shownAtMs = recentShown(next, ctx.nowMs) + ctx.nowMs,
            lastBannerAtMs = ctx.nowMs,
        )
        return banner to next
    }

    /** When the one-shot local timer should fire next, or null when nothing is held. No network. */
    fun nextTimerAtMs(ledger: AlertLedger): Long? = ledger.pending.minOfOrNull { it.dueAtMs }

    /** The games with a watch-check hold, for which the controller must run the lookup. */
    fun watchCheckEventIds(ledger: AlertLedger): Set<String> =
        ledger.pending.filter { it.reason == HoldReason.WatchCheck }.map { it.alert.eventId }.toSet()

    // ---- gates ----

    /** Age of the moment; an `at` more than a minute ahead of this device's clock is skew, measured from receipt. */
    private fun ageMs(alert: SportsAlert, ctx: AlertContext): Long =
        if (alert.atMs - ctx.nowMs > CLOCK_SKEW_MS) 0L else ctx.nowMs - alert.atMs

    private fun gate2(alert: SportsAlert, settings: SportsAlertSettings, ctx: AlertContext): DropReason? {
        val followedTeams = listOfNotNull(alert.homeId, alert.awayId).filter { it in ctx.followedTeamIds }
        val leagueFollowed = alert.leagueId != null && alert.leagueId in ctx.followedLeagueIds
        if (followedTeams.isEmpty() && !leagueFollowed) return DropReason.NotFollowed
        val override = settings.gameOverride(alert.eventId, ctx.nowMs)
        if (override?.isOff == true) return DropReason.Muted
        // A mute (league or team) wins over a per-game "all".
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
    private fun gateRelease(alert: SportsAlert, settings: SportsAlertSettings, ctx: AlertContext): Gate {
        if (ctx.openGameEventId == alert.eventId) return Gate.Drop(DropReason.WatchingThisGame)
        val watch = ctx.watchStates[alert.eventId] ?: WatchState.Unknown
        if (watch is WatchState.Channel && watch.ids.any { it in ctx.playingChannelIds }) return Gate.Drop(DropReason.WatchingThisGame)
        if (inQuietHours(settings, ctx.localMinuteOfDay)) return Gate.Drop(DropReason.QuietHours)
        val blocked = when (settings.duringPlayback) {
            DuringPlayback.Always -> false
            DuringPlayback.Never -> ctx.playback != PlaybackContext.None
            DuringPlayback.LiveTvOnly -> ctx.playback == PlaybackContext.Vod
        }
        if (blocked) return Gate.Drop(DropReason.DuringPlayback)
        // Live TV is up, a channel is playing, and nobody knows whether it is THIS game: a goal could spoil it.
        if (watch == WatchState.Unknown && ctx.playback == PlaybackContext.LiveTv && ctx.playingChannelIds.isNotEmpty() &&
            alert.kind != AlertKind.Start
        ) return Gate.Check
        return Gate.Pass
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
                ledger.copy(pending = ledger.pending + Held(alert, batchDue, HoldReason.Batch, ctx.nowMs)).count("held:${HoldReason.Batch.wire}")
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
        val slugs = perGame.map { SportsAlertTopicPolicy.slugOf(it.sport) ?: "other" }.distinct()
        return AlertBanner(
            title = "${perGame.size} sports updates", detail = null, lines = lines, eventId = null,
            sport = slugs.singleOrNull() ?: "other", size = perGame.size,
        )
    }

    private fun single(alert: SportsAlert, ctx: AlertContext): AlertBanner = AlertBanner(
        title = line(alert, ctx),
        detail = listOfNotNull(alert.league, alert.progress).joinToString(" · ").ifBlank { null },
        eventId = alert.eventId,
        kind = alert.kind,
        sport = SportsAlertTopicPolicy.slugOf(alert.sport) ?: "other",
    )

    /** One line of text for an alert, numbers only when the policy lets them show. A blank away shows the home name alone. */
    fun line(alert: SportsAlert, ctx: AlertContext): String {
        val teams = if (alert.away.isBlank()) alert.home else "${alert.home} v ${alert.away}"
        val withScore = if (alert.homeScore != null && alert.awayScore != null && alert.away.isNotBlank() && !scoresHidden(alert, ctx)) {
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
        AlertDecision.Drop(reason) to (
            if (reason.wire in SportsAlertTelemetry.COUNT_ONLY_REASONS) ledger.count("dropped:${reason.wire}") else ledger
            )

    private fun AlertLedger.count(key: String): AlertLedger = copy(counts = counts.bump(key))

    private fun Map<String, Int>.bump(key: String, by: Int = 1): Map<String, Int> = this + (key to ((this[key] ?: 0) + by))

    private fun Set<String>.trimmedToCap(): Set<String> =
        if (size <= SEEN_KEYS_CAP) this else drop(size - SEEN_KEYS_CAP).toCollection(LinkedHashSet())
}
