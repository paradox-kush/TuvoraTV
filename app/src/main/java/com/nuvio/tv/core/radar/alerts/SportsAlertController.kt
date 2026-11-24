package com.nuvio.tv.core.radar.alerts

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.nuvio.tv.core.contracts.AlertAction
import com.nuvio.tv.core.radar.RadarLocalState
import com.nuvio.tv.core.radar.RadarStore
import com.nuvio.tv.core.radar.RevealedGames
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SportsAlertController"

/**
 * The Android side of in-app sports alerts: feeds [SportsAlertEngine] with the radar store (settings, follows), the
 * route (playback, open game), the playing channels and the session's revealed games, and owns the hold-OK key
 * handling. Composables only read [banner]; they do no I/O. The Realtime plumbing lives in [SportsAlertChannels] /
 * [SportsAlertSubscription].
 */
@Singleton
class SportsAlertController @Inject constructor(
    private val store: RadarStore,
    private val revealedGames: RevealedGames,
    private val watchLookup: SportsAlertWatchLookup,
    private val playingChannels: SportsAlertPlayingChannels,
) : com.nuvio.tv.core.contracts.SportsAlertsHost {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "alert handling failed", e) },
    )

    @Volatile private var route = SportsAlertRouteContext()

    // Settings, follows and the profile come straight from the radar store (no network, no repository load).
    private val state: StateFlow<RadarLocalState> by lazy { store.state.stateIn(scope, SharingStarted.Eagerly, RadarLocalState()) }

    private val engine = SportsAlertEngine(
        scope = scope,
        clock = System::currentTimeMillis,
        settings = { state.value.prefs.alerts },
        context = ::context,
        watch = watchLookup,
        telemetry = ::telemetry,
        onBannerCleared = { handler.post { bannerGone() } },
    )

    /** The banner on screen right now, or null. Not focusable; auto-dismisses after ten seconds. */
    val banner: StateFlow<AlertBanner?> get() = engine.banner

    /** Non-null once the lookup found a confirmed channel while this banner is up: the action reads Watch. */
    val watchContentId: StateFlow<String?> get() = engine.watchContentId

    /** One-shot: the NavHost collects it and opens the destination. */
    override val actions: SharedFlow<AlertAction> get() = engine.actions

    /** Touch the lazy state flow early so the first alert never reads the empty default. */
    fun start() { state.value }

    /** Called by the host whenever the navigation destination changes. */
    fun onRoute(context: SportsAlertRouteContext) { route = context }

    override fun onRoute(route: String?, contentType: String?, contentId: String?, gameId: String?) =
        onRoute(SportsAlertRouteContext.from(route, contentType, contentId, gameId))

    override fun publishLineup(gameId: String): Boolean = watchLookup.publishLineup(gameId)

    /** One broadcast payload from a joined channel. Never throws. */
    fun onPayload(payload: JsonObject) {
        SportsAlertPayload.parse(payload)?.let(engine::onAlert)
    }

    fun onBackground() = engine.onBackground()

    fun onProfileChanged() {
        watchLookup.clear()
        engine.onProfileChanged()
    }

    fun dismissByUser() = engine.dismissByUser()

    private fun context(nowMs: Long): AlertContext {
        val s = state.value
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val r = route
        // Every channel playing now: the clean live player's, each multiview tile, and the legacy player's live channel.
        val playing = playingChannels.ids.value + listOfNotNull(r.playingContentId.takeIf { r.playback == PlaybackContext.LiveTv })
        return AlertContext(
            nowMs = nowMs,
            localMinuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            playback = r.playback,
            openGameEventId = r.openGameEventId,
            playingChannelIds = playing,
            watchStates = watchLookup.statesFor(playing, nowMs),
            followedLeagueIds = s.follows.map { it.leagueId }.toSet(),
            followedTeamIds = s.teams.map { it.teamId }.toSet(),
            spoiler = s.prefs.spoiler,
            revealed = revealedGames.current(),
        )
    }

    // ---- hold OK (activity key dispatch, main thread; the decisions live in AlertHoldKeyPolicy) ----

    private var hold = AlertHoldKeyPolicy.State()
    /** The swallowed OK down of the tracked press, kept so a short press (or a vanished banner) can give it back. */
    private var heldDown: KeyEvent? = null
    /** The banner the tracked press began on: a banner replaced mid-press must not fire its successor's action. */
    private var heldBannerSeq = 0L
    private var replaying = false
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Re-delivers a key to the app exactly as the activity would have (set by MainActivity: `super.dispatchKeyEvent`). */
    @Volatile override var replaySink: ((KeyEvent) -> Boolean)? = null

    private val holdTimer = Runnable {
        val step = AlertHoldKeyPolicy.onTimer(hold, banner.value != null, SystemClock.uptimeMillis())
        apply(step, up = null)
    }

    private fun bannerGone() = apply(AlertHoldKeyPolicy.onBannerGone(hold), up = null)

    private fun apply(step: AlertHoldKeyPolicy.Step, up: KeyEvent?) {
        hold = step.state
        if (step.state.downAtMs == null) handler.removeCallbacks(holdTimer)
        if (step.replay != AlertHoldKeyPolicy.Replay.None) replay(step.replay, up)
        if (step.fire) { heldDown = null; engine.runAction(expectedSequence = heldBannerSeq) }
    }

    private fun replay(kind: AlertHoldKeyPolicy.Replay, up: KeyEvent?) {
        val down = heldDown
        heldDown = null
        val sink = replaySink ?: return
        replaying = true
        try {
            down?.let { sink(it) }
            if (kind == AlertHoldKeyPolicy.Replay.DownUp && up != null) sink(up)
        } finally {
            replaying = false
        }
    }

    /**
     * The activity paused: a press in flight loses its up for good, so it is forgotten (never replayed into a paused
     * activity, never fired later by the timer).
     */
    override fun onActivityPaused() {
        handler.removeCallbacks(holdTimer)
        hold = AlertHoldKeyPolicy.onPause(hold).state
        heldDown = null
    }

    /**
     * Called first thing in `MainActivity.dispatchKeyEvent`. Returns true when the event must be swallowed: an OK down
     * while a banner is up (it may be the start of a hold), the rest of a held OK, and a short press's up (the press is
     * replayed whole through [replaySink]). Any other key, and OK with no banner, returns false and flows on untouched.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (replaying) return false
        val phase = when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) AlertHoldKeyPolicy.Phase.Down else AlertHoldKeyPolicy.Phase.Repeat
            KeyEvent.ACTION_UP -> AlertHoldKeyPolicy.Phase.Up
            else -> return false
        }
        // Fast path: no banner and no press being tracked means nothing to do (the common case).
        if (banner.value == null && hold.downAtMs == null && !hold.fired) return false
        val step = AlertHoldKeyPolicy.onKey(hold, banner.value != null, AlertHoldKeyPolicy.isOkKey(event.keyCode), phase, event.eventTime)
        val startsTracking = phase == AlertHoldKeyPolicy.Phase.Down && step.state.downAtMs != null && step.consume
        if (startsTracking) {
            if (replaySink == null) return false // nothing could give the press back: leave it alone
            heldDown = KeyEvent(event)
            heldBannerSeq = engine.bannerSequence
            handler.removeCallbacks(holdTimer)
            handler.postDelayed(holdTimer, AlertHoldKeyPolicy.HOLD_MS)
        }
        apply(step, up = event.takeIf { phase == AlertHoldKeyPolicy.Phase.Up })
        return step.consume
    }

    private fun telemetry(properties: Map<String, Any>) {
        runCatching { com.posthog.PostHog.capture(event = SportsAlertTelemetry.EVENT, properties = properties) }
    }

    companion object {
        const val BANNER_SHOWN_MS = SportsAlertEngine.BANNER_SHOWN_MS
    }
}
