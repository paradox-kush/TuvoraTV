package com.nuvio.tv.core.telemetry

import java.util.concurrent.atomic.AtomicBoolean

/** Where the funnel state lives: one string. Production = the telemetry SharedPreferences (never the wiped `telemetry/` dir). */
interface FirstRunStateStore {
    fun read(): String?
    fun write(value: String)
}

/**
 * The first-run funnel's stateful half: state under one lock, the once-gate, events held until the SDK is ready,
 * consent checked at SEND time. All decisions are [FirstRunFunnel]'s; this only sequences them.
 *
 * Steps can arrive from any thread (a sync pull, a player thread). The read-modify-write of the state is under
 * [lock], so two calls for the same step emit one event. A step that happens while consent is off is still
 * marked done (the state moves on) and is never sent later: consent is read when the event is delivered, and a
 * dropped event is gone.
 *
 * @param sink where the event goes (production: `PostHog.capture`, which the gate then sends explicitly).
 * @param consentEnabled the "Crash & usage reports" switch, read at delivery.
 * @param supportCodeExistedAtStart a support code was already on disk before this process generated one.
 * @param flush asks the analytics SDK to upload what it holds (production: `PostHog.flush()` off the main thread). Called
 * right after a `first_leave` was captured, because the process may be suspended or killed any moment after backgrounding.
 */
class FirstRunFunnelService(
    private val store: FirstRunStateStore,
    private val nowMs: () -> Long,
    private val consentEnabled: () -> Boolean,
    private val sink: (String, Map<String, Any>) -> Unit,
    private val supportCodeExistedAtStart: Boolean,
    private val flush: () -> Unit = {},
) {
    private val lock = Any()
    private var state: FirstRunState? = null
    private var started = false
    private val visit = FirstLeaveVisit(nowMs())

    private val sdkReady = AtomicBoolean(false)
    private val held = ArrayList<Map<String, Any>>()

    /**
     * Once per process (a second call is ignored). Decides new vs existing ONCE when no state is persisted:
     * existing = a support code was on disk at start, or [otherExistingSignals] (playlists, add-ons, watch
     * history in any profile; asked only then). Call it before anything else creates user state.
     */
    fun start(otherExistingSignals: () -> Boolean) {
        val emit = synchronized(lock) {
            if (started) return
            started = true
            val stored = FirstRunStateCodec.decode(runCatching { store.read() }.getOrNull())
            val t = FirstRunFunnel.start(stored, nowMs()) {
                // A failing probe is "we cannot tell": err towards silence (existing), never a false first_launch.
                supportCodeExistedAtStart || runCatching(otherExistingSignals).getOrDefault(true)
            }
            commit(stored, t)
        }
        emit?.let(::deliver)
    }

    fun step(step: FirstRunStep, props: Map<String, Any> = emptyMap()) {
        val emit = synchronized(lock) {
            val t = FirstRunFunnel.record(state, step, nowMs(), props)
            commit(state, t)
        }
        emit?.let(::deliver)
    }

    /** A first-visit fact for `first_leave`. Persisted ones go through the same lock and store as the steps; nothing is sent here. */
    fun note(note: FirstRunNote) {
        synchronized(lock) {
            if (note is FirstRunNote.Screen) { visit.screen(note.name); return }
            val before = state
            val next = FirstRunFunnel.note(before, note) ?: return
            commit(before, FirstRunTransition(next, null))
        }
    }

    fun noteScreen(name: String) = note(FirstRunNote.Screen(name))

    /** The whole app came back to the foreground: a new foreground stint starts (`secs_in_app`). */
    fun onForeground() {
        val now = nowMs()
        synchronized(lock) { visit.foreground(now) }
    }

    /**
     * The whole app left the foreground. The first time this happens on a new install before anything played, the
     * `first_leave` summary is captured under the lock (two concurrent signals emit one), delivered, and the SDK is asked
     * to flush. Once-gate, consent and the existing-install rule are the funnel's.
     */
    fun onBackground() {
        val now = nowMs()
        val emit = synchronized(lock) {
            commit(state, FirstRunFunnel.record(state, FirstRunStep.FIRST_LEAVE, now, visit.props(now)))
        } ?: return
        deliver(emit)
        runCatching(flush)
    }

    /** The analytics SDK is set up: events held so far are delivered (consent checked now). */
    fun markSdkReady() {
        sdkReady.set(true)
        flushHeld()
    }

    /** Caller holds [lock]. Persists a changed state (best effort: a failed write keeps the in-memory gate). */
    private fun commit(before: FirstRunState?, t: FirstRunTransition): Map<String, Any>? {
        val next = t.state
        if (next != null) {
            state = next
            if (next != before) runCatching { store.write(FirstRunStateCodec.encode(next)) }
        } else if (state == null) {
            state = before
        }
        return t.emit
    }

    private fun deliver(props: Map<String, Any>) {
        if (!sdkReady.get()) {
            synchronized(held) { held.add(props) }
            // The SDK may have come up between the check and the add.
            if (sdkReady.get()) flushHeld()
            return
        }
        send(props)
    }

    private fun flushHeld() {
        val batch = synchronized(held) { held.toList().also { held.clear() } }
        batch.forEach(::send)
    }

    private fun send(props: Map<String, Any>) {
        if (!consentEnabled()) return
        runCatching { sink(FirstRunFunnel.EVENT, props) }
    }
}
