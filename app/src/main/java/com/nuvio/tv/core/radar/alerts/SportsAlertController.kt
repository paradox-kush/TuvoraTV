package com.nuvio.tv.core.radar.alerts

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.nuvio.tv.core.radar.RadarLocalState
import com.nuvio.tv.core.radar.RadarStore
import com.nuvio.tv.core.radar.RevealedGames
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SportsAlertController"

/** What a banner does when the viewer holds OK. Navigation lives in the NavHost; this only says what to open. */
sealed interface AlertAction {
    data class OpenGame(val gameId: String) : AlertAction
    data class Watch(val gameId: String, val contentId: String) : AlertAction
    data object OpenSports : AlertAction
}

/**
 * The state holder of in-app sports alerts: runs [SportsAlertPolicy] on every broadcast, owns the ledger, the one-shot
 * local timer for held alerts, the current banner and its action, and the hold-OK key handling. Composables only read
 * [banner]; they do no I/O. The Realtime plumbing lives in [SportsAlertChannels] / [SportsAlertSubscription].
 */
@Singleton
class SportsAlertController @Inject constructor(
    private val store: RadarStore,
    private val revealedGames: RevealedGames,
    private val watchLookup: SportsAlertWatchLookup,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "alert handling failed", e) },
    )
    private val lock = Mutex()
    private var ledger = AlertLedger()
    private var timerJob: Job? = null
    private var dismissJob: Job? = null
    private var lookupJob: Job? = null
    private var bannerSeq = 0L

    private val _banner = MutableStateFlow<AlertBanner?>(null)
    /** The banner on screen right now, or null. Not focusable; auto-dismisses after [BANNER_SHOWN_MS]. */
    val banner: StateFlow<AlertBanner?> = _banner.asStateFlow()

    private val _watchContentId = MutableStateFlow<String?>(null)
    /** Non-null once the game page's lookup found a confirmed channel while this banner is up: the action reads Watch. */
    val watchContentId: StateFlow<String?> = _watchContentId.asStateFlow()

    private val _actions = MutableSharedFlow<AlertAction>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** One-shot: the NavHost collects it and opens the destination. */
    val actions: SharedFlow<AlertAction> = _actions.asSharedFlow()

    @Volatile private var route = SportsAlertRouteContext()

    // Settings, follows and the profile come straight from the radar store (no network, no repository load).
    private val state: StateFlow<RadarLocalState> by lazy { store.state.stateIn(scope, SharingStarted.Eagerly, RadarLocalState()) }

    /** Touch the lazy state flow early so the first alert never reads the empty default. */
    fun start() { state.value }

    /** Called by the host whenever the navigation destination changes. */
    fun onRoute(context: SportsAlertRouteContext) { route = context }

    /** One broadcast payload from a joined channel. Never throws. */
    fun onPayload(payload: JsonObject) {
        val alert = SportsAlertPayload.parse(payload, System.currentTimeMillis()) ?: return
        scope.launch { lock.withLock { handle(alert) } }
    }

    private fun context(nowMs: Long): AlertContext {
        val s = state.value
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val r = route
        val watching = r.playingContentId?.let { playing ->
            watchLookup.cachedContentIds().entries.firstOrNull { it.value == playing }?.key
        }
        return AlertContext(
            nowMs = nowMs,
            localMinuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            playback = r.playback,
            openGameEventId = r.openGameEventId,
            watchingEventId = watching,
            followedLeagueIds = s.follows.map { it.leagueId }.toSet(),
            followedTeamIds = s.teams.map { it.teamId }.toSet(),
            spoiler = s.prefs.spoiler,
            revealed = revealedGames.current(),
        )
    }

    private fun handle(alert: SportsAlert) {
        val settings = state.value.prefs.alerts
        val ctx = context(System.currentTimeMillis())
        val (decision, next) = SportsAlertPolicy.onAlert(alert, settings, ctx, ledger)
        ledger = next
        when (decision) {
            is AlertDecision.ShowNow -> { telemetry(SportsAlertTelemetry.shown(decision.banner)); show(decision.banner) }
            is AlertDecision.HoldUntil -> { telemetry(SportsAlertTelemetry.held(alert, decision.reason)); rescheduleTimer() }
            is AlertDecision.Drop -> telemetry(SportsAlertTelemetry.dropped(alert, decision.reason))
        }
    }

    /** One-shot local timer to the earliest held alert. No polling, no network. */
    private fun rescheduleTimer() {
        timerJob?.cancel()
        val due = SportsAlertPolicy.nextTimerAtMs(ledger) ?: return
        timerJob = scope.launch {
            delay((due - System.currentTimeMillis()).coerceAtLeast(0L))
            lock.withLock {
                val ctx = context(System.currentTimeMillis())
                val (banner, next) = SportsAlertPolicy.onTimer(state.value.prefs.alerts, ctx, ledger)
                ledger = next
                if (banner != null) { telemetry(SportsAlertTelemetry.shown(banner)); show(banner) }
                rescheduleTimer()
            }
        }
    }

    private fun show(banner: AlertBanner) {
        val seq = ++bannerSeq
        _watchContentId.value = null
        _banner.value = banner
        lookupJob?.cancel()
        // Final / summary banners never offer Watch: a finished game has nothing to watch, a summary opens Sports.
        val eventId = banner.eventId
        if (eventId != null && banner.kind != AlertKind.Final) {
            lookupJob = scope.launch {
                val hit = watchLookup.find(eventId)
                if (hit != null && bannerSeq == seq && _banner.value == banner) _watchContentId.value = hit.contentId
            }
        }
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(BANNER_SHOWN_MS)
            if (bannerSeq == seq) dismiss()
        }
    }

    fun dismiss() {
        lookupJob?.cancel()
        dismissJob?.cancel()
        _banner.value = null
        _watchContentId.value = null
    }

    /** Runs the banner's action (hold OK). */
    fun runAction() {
        val b = _banner.value ?: return
        val eventId = b.eventId
        val action: AlertAction
        val label: String
        when {
            eventId == null -> { action = AlertAction.OpenSports; label = "open_sports" }
            _watchContentId.value != null -> { action = AlertAction.Watch(eventId, _watchContentId.value!!); label = "watch" }
            else -> { action = AlertAction.OpenGame(eventId); label = "open_game" }
        }
        telemetry(SportsAlertTelemetry.action(b, label))
        dismiss()
        _actions.tryEmit(action)
    }

    /** A profile switch forgets held alerts, the seen keys and the lookups of the old profile. */
    fun onProfileChanged() {
        scope.launch {
            lock.withLock {
                ledger = AlertLedger()
                timerJob?.cancel()
            }
            watchLookup.clear()
            dismiss()
        }
    }

    // ---- hold OK (activity key dispatch, main thread) ----

    private var hold = AlertHoldKeyPolicy.State()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val holdTimer = Runnable {
        val step = AlertHoldKeyPolicy.onTimer(hold, _banner.value != null, SystemClock.uptimeMillis())
        hold = step.state
        if (step.fire) runAction()
    }

    /**
     * Called first thing in `MainActivity.dispatchKeyEvent`. Returns true when the event must be swallowed (the rest of
     * a held OK after the action fired). A short press, and any other key, returns false and flows on untouched.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val phase = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) AlertHoldKeyPolicy.Phase.Down else AlertHoldKeyPolicy.Phase.Repeat
            KeyEvent.ACTION_UP -> AlertHoldKeyPolicy.Phase.Up
            else -> return false
        }
        val isOk = AlertHoldKeyPolicy.isOkKey(event.keyCode)
        // Fast path: no banner and no press being tracked means nothing to do (the common case).
        if (_banner.value == null && hold.downAtMs == null && !hold.fired) return false
        val step = AlertHoldKeyPolicy.onKey(hold, _banner.value != null, isOk, phase, event.eventTime)
        hold = step.state
        if (isOk) {
            when (phase) {
                AlertHoldKeyPolicy.Phase.Down -> {
                    handler.removeCallbacks(holdTimer)
                    if (hold.downAtMs != null) handler.postDelayed(holdTimer, AlertHoldKeyPolicy.HOLD_MS)
                }
                AlertHoldKeyPolicy.Phase.Up -> handler.removeCallbacks(holdTimer)
                AlertHoldKeyPolicy.Phase.Repeat -> Unit
            }
        }
        if (step.fire) runAction()
        return step.consume
    }

    private fun telemetry(properties: Map<String, Any>) {
        runCatching { com.posthog.PostHog.capture(event = SportsAlertTelemetry.EVENT, properties = properties) }
    }

    companion object {
        /** TV: ten seconds (the spec's TV / Apple TV figure). */
        const val BANNER_SHOWN_MS = 10_000L
    }
}
