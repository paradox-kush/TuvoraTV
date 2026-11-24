package com.nuvio.tv.core.radar.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a banner does when the viewer holds OK. Navigation lives in the NavHost; this only says what to open. */
sealed interface AlertAction {
    data class OpenGame(val gameId: String) : AlertAction
    data class Watch(val gameId: String, val contentId: String) : AlertAction
    data object OpenSports : AlertAction
}

/** The watch lookup as the engine sees it (the real one needs the matcher; tests pass a fake). */
interface AlertWatchPort {
    fun statesFor(playing: Set<String>, nowMs: Long): Map<String, WatchState>

    /** True = the game now has a cached answer (found or none); false = could not tell, nothing cached. */
    suspend fun resolve(eventId: String): Boolean

    /** The Watch target (a confirmed channel), only while the cached answer is fresh. */
    fun watchContentId(eventId: String, nowMs: Long): String?
}

/**
 * The state holder of in-app sports alerts, free of Android so its flow (receive, hold, lookup, re-evaluate, timer,
 * banner) tests with a virtual clock. EVERY state change runs under one mutex: alert arrival, timer flush, lookup
 * answer, auto-dismiss, action, background. [SportsAlertController] wraps it with the Android bits (store, route, keys).
 */
class SportsAlertEngine(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val settings: () -> SportsAlertSettings,
    private val context: (Long) -> AlertContext,
    private val watch: AlertWatchPort,
    private val telemetry: (Map<String, Any>) -> Unit,
    /** Called (under the lock) whenever the banner goes away, for any reason. */
    private val onBannerCleared: () -> Unit = {},
) {
    private val lock = Mutex()
    private var ledger = AlertLedger()
    private var timerJob: Job? = null
    private var dismissJob: Job? = null
    private var lookupJob: Job? = null
    private val checked = HashSet<String>()
    private val checkJobs = HashMap<String, Job>()

    @Volatile var bannerSequence = 0L
        private set

    private val _banner = MutableStateFlow<AlertBanner?>(null)
    val banner: StateFlow<AlertBanner?> = _banner.asStateFlow()

    private val _watchContentId = MutableStateFlow<String?>(null)
    val watchContentId: StateFlow<String?> = _watchContentId.asStateFlow()

    private val _actions = MutableSharedFlow<AlertAction>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val actions: SharedFlow<AlertAction> = _actions.asSharedFlow()

    /** Diagnostics and tests: the held alerts and the count-only tallies. */
    suspend fun ledgerSnapshot(): AlertLedger = lock.withLock { ledger }

    fun onAlert(alert: SportsAlert) { scope.launch { receive(alert) } }

    suspend fun receive(alert: SportsAlert) = lock.withLock {
        val before = ledger.counts
        val (decision, next) = SportsAlertPolicy.onAlert(alert, settings(), context(clock()), ledger)
        ledger = next
        emitCounts(before, next.counts)
        if (decision is AlertDecision.ShowNow) { telemetry(SportsAlertTelemetry.shown(decision.banner)); show(decision.banner) }
        afterPolicy()
    }

    /** Runs the policy's flush: due holds, plus the watch checks that just got an answer. */
    private fun flush(resolved: Set<String> = emptySet()) {
        val before = ledger.counts
        val (banner, next) = SportsAlertPolicy.onTimer(settings(), context(clock()), ledger, resolved)
        ledger = next
        emitCounts(before, next.counts)
        if (banner != null) { telemetry(SportsAlertTelemetry.shown(banner)); show(banner) }
        afterPolicy()
    }

    private fun afterPolicy() {
        startWatchChecks()
        rescheduleTimer()
    }

    /** One lookup per held game (the answer releases its hold at once; no answer = the deadline ends it). */
    private fun startWatchChecks() {
        val ids = SportsAlertPolicy.watchCheckEventIds(ledger)
        checked.retainAll(ids)
        ids.forEach { id ->
            if (!checked.add(id)) return@forEach
            checkJobs[id] = scope.launch {
                if (watch.resolve(id)) lock.withLock { flush(resolved = setOf(id)) }
            }
        }
        checkJobs.keys.retainAll(ids)
    }

    /** One-shot local timer to the earliest held alert. No polling, no network. */
    private fun rescheduleTimer() {
        timerJob?.cancel()
        val due = SportsAlertPolicy.nextTimerAtMs(ledger) ?: return
        timerJob = scope.launch {
            delay((due - clock()).coerceAtLeast(0L))
            lock.withLock { flush() }
        }
    }

    private fun show(banner: AlertBanner) {
        val seq = ++bannerSequence
        _watchContentId.value = null
        _banner.value = banner
        lookupJob?.cancel()
        // Score/Start banners may offer Watch; a Final (or summary) banner offers Open game and never looks anything up.
        val eventId = banner.eventId
        if (eventId != null && SportsAlertWatchPolicy.bannerLookupWanted(banner)) {
            lookupJob = scope.launch {
                if (watch.resolve(eventId)) lock.withLock {
                    if (bannerSequence == seq) _watchContentId.value = watch.watchContentId(eventId, clock())
                }
            }
        }
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(BANNER_SHOWN_MS)
            // Auto-dismiss: not a user choice, so no event.
            lock.withLock { if (bannerSequence == seq) clearBanner() }
        }
    }

    private fun clearBanner() {
        lookupJob?.cancel()
        dismissJob?.cancel()
        val had = _banner.value != null
        _banner.value = null
        _watchContentId.value = null
        if (had) onBannerCleared()
    }

    /** The viewer dismissed it (the only source of the `dismissed` action). */
    fun dismissByUser() {
        scope.launch {
            lock.withLock {
                val b = _banner.value ?: return@withLock
                telemetry(SportsAlertTelemetry.action(b, SportsAlertTelemetry.ACTION_DISMISSED))
                clearBanner()
            }
        }
    }

    /**
     * Runs the banner's action (hold OK). [expectedSequence] is the banner the press began on: a banner that was
     * replaced mid-press never fires its successor's action.
     */
    fun runAction(expectedSequence: Long? = null) {
        scope.launch {
            lock.withLock {
                val b = _banner.value ?: return@withLock
                if (expectedSequence != null && expectedSequence != bannerSequence) return@withLock
                val eventId = b.eventId
                val watchId = _watchContentId.value
                val action: AlertAction
                val label: String
                when {
                    eventId == null -> { action = AlertAction.OpenSports; label = "open_sports" }
                    watchId != null -> { action = AlertAction.Watch(eventId, watchId); label = "watch" }
                    else -> { action = AlertAction.OpenGame(eventId); label = "open_game" }
                }
                telemetry(SportsAlertTelemetry.action(b, label))
                clearBanner()
                _actions.tryEmit(action)
            }
        }
    }

    /** Going to the background clears every hold (an alert is only worth showing while the app is looked at). */
    fun onBackground() {
        scope.launch {
            lock.withLock {
                ledger = ledger.copy(pending = emptyList())
                timerJob?.cancel()
                checkJobs.values.forEach { it.cancel() }
                checkJobs.clear(); checked.clear()
                clearBanner()
            }
        }
    }

    /** A profile switch forgets held alerts, the seen keys and the banner of the old profile. */
    fun onProfileChanged() {
        scope.launch {
            lock.withLock {
                ledger = AlertLedger()
                timerJob?.cancel()
                checkJobs.values.forEach { it.cancel() }
                checkJobs.clear(); checked.clear()
                clearBanner()
            }
        }
    }

    /** Held/dropped tallies become `sports_alert` events the telemetry policy only COUNTS (never one event each). */
    private fun emitCounts(before: Map<String, Int>, after: Map<String, Int>) {
        after.forEach { (key, n) ->
            val delta = n - (before[key] ?: 0)
            if (delta <= 0) return@forEach
            val (outcome, reason) = key.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "other" } }
            repeat(minOf(delta, MAX_COUNT_EVENTS)) { telemetry(mapOf("outcome" to outcome, "reason" to reason)) }
        }
    }

    companion object {
        /** TV: ten seconds (the spec's TV / Apple TV figure). */
        const val BANNER_SHOWN_MS = 10_000L
        private const val MAX_COUNT_EVENTS = 10
    }
}
