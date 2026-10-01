package com.nuvio.tv.core.iptv

import android.content.Context
import androidx.core.content.edit
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Step 0.3 port — where a playlist's [ServerFailoverState] lives, per (profile, playlist key).
 * Device-local, never synced. Production: [PrefsServerFailoverStateStore]; tests use the in-memory one.
 */
interface ServerFailoverStateStore {
    fun read(profileId: Int, playlistKey: String): ServerFailoverState
    fun write(profileId: Int, playlistKey: String, state: ServerFailoverState)
    fun clear(profileId: Int, playlistKey: String)
    /** Every non-default state of [profileId], keyed by playlist key (drives the "Using backup" row). */
    fun all(profileId: Int): Map<String, ServerFailoverState>
}

/** In-memory [ServerFailoverStateStore] — the test double, and the shape the prefs store caches. */
class InMemoryServerFailoverStateStore : ServerFailoverStateStore {
    private val states = ConcurrentHashMap<Int, Map<String, ServerFailoverState>>()

    override fun read(profileId: Int, playlistKey: String) =
        states[profileId]?.get(playlistKey) ?: ServerFailoverState()

    @Synchronized
    override fun write(profileId: Int, playlistKey: String, state: ServerFailoverState) {
        val profile = states[profileId].orEmpty()
        states[profileId] = if (state == ServerFailoverState()) profile - playlistKey else profile + (playlistKey to state)
    }

    override fun clear(profileId: Int, playlistKey: String) = write(profileId, playlistKey, ServerFailoverState())
    override fun all(profileId: Int): Map<String, ServerFailoverState> = states[profileId].orEmpty()
}

/**
 * The production store: one JSON map per profile in its own SharedPreferences file, cached in memory
 * so building a stream URL never waits on disk. SharedPreferences (not the DataStore most TV state
 * uses) for the [CatchUpWinnerPrefs] reason: stream/catch-up URLs are built synchronously, and this
 * is device-local knowledge about THIS device's network — nothing to follow the account elsewhere.
 * Only non-default states are kept, so a playlist on its main server costs nothing.
 */
@Singleton
class PrefsServerFailoverStateStore @Inject constructor(
    @ApplicationContext context: Context,
) : ServerFailoverStateStore {
    private val prefs = context.getSharedPreferences("iptv_server_failover", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<Int, Map<String, ServerFailoverState>>()

    private fun key(profileId: Int) = "profile_$profileId"

    private fun profile(profileId: Int): Map<String, ServerFailoverState> = cache.getOrPut(profileId) {
        runCatching { prefs.getString(key(profileId), null)?.let(::decode) }.getOrNull().orEmpty()
    }

    override fun read(profileId: Int, playlistKey: String) = profile(profileId)[playlistKey] ?: ServerFailoverState()

    @Synchronized
    override fun write(profileId: Int, playlistKey: String, state: ServerFailoverState) {
        val current = profile(profileId)
        val next = if (state == ServerFailoverState()) current - playlistKey else current + (playlistKey to state)
        if (next == current) return
        cache[profileId] = next
        prefs.edit {
            if (next.isEmpty()) remove(key(profileId)) else putString(key(profileId), encode(next))
        }
    }

    override fun clear(profileId: Int, playlistKey: String) = write(profileId, playlistKey, ServerFailoverState())

    override fun all(profileId: Int): Map<String, ServerFailoverState> = profile(profileId)

    /**
     * The persisted shape — the same JSON NuvioMobile/NuvioDesktop write (kotlinx, defaults omitted, so a
     * playlist without stats is exactly the Step 0.3 row). Step 0.3 wrote these rows with Gson in the
     * same shape; unknown keys from a newer build are ignored (golden: `FailoverRaceGolden.persistenceCases`).
     */
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), ServerFailoverState.serializer())

        internal fun decode(raw: String): Map<String, ServerFailoverState> = json.decodeFromString(serializer, raw)
        internal fun encode(states: Map<String, ServerFailoverState>): String = json.encodeToString(serializer, states)
        internal fun decodeState(raw: String): ServerFailoverState = json.decodeFromString(ServerFailoverState.serializer(), raw)
        internal fun encodeState(state: ServerFailoverState): String = json.encodeToString(ServerFailoverState.serializer(), state)
    }
}

/**
 * How a fail-over-able request's answer was obtained — TV only. Xtream catalog calls ride OkHttp's
 * disk cache (NetworkModule): a fresh hit never touched the host ([CACHE] — proves nothing, so the
 * state is left alone and no other server is tried), and [XtreamCatalogFallbackInterceptor] serves a
 * stale copy when the host FAILED ([STALE_FALLBACK] — the host is down, so the walk moves on and the
 * stale copy is only the last resort). Everything else is [NETWORK].
 */
enum class ServedFrom { NETWORK, CACHE, STALE_FALLBACK }

/**
 * Step 0.3 — THE seam every fail-over-able IPTV request goes through, and the one place that knows a
 * playlist's ACTIVE server. TV twin of NuvioMobile/NuvioDesktop `PlaylistServerFailover` (an object
 * there; a Hilt singleton here, same behaviour).
 *
 * FAILS OVER (via [run]): the Xtream login/account call and catalog calls (categories, streams,
 * series, series/vod info, short EPG, the EPG table, derived xmltv.php), the M3U-link playlist
 * download, and Stalker handshake/profile/browse calls. NEVER fails over: stream/playback URLs,
 * Stalker create_link, catch-up/timeshift URLs — those are built on [activeAccount]'s server (or
 * moved there by [rebaseStreamUrl]) and their failures never touch this state.
 *
 * Decisions are [ServerFailoverPolicy] (order/state), [FailoverFailureClassifier] (which error moves
 * on), [FailoverRaceScheduler] (when the next server is tried) and [FailoverProbePolicy] (what a valid
 * probe looks like); this class only executes them. A playlist with no backups takes the original
 * single-request path untouched — no state is read or written.
 *
 * Step 0.3b — STAGGERED PARALLEL FAILOVER. The real request goes to the first server of the policy's
 * order, alone: a healthy playlist makes exactly ONE request, no probe. Only when that request fails
 * (fail-over-able) or has produced no response headers within the server's stagger does the walk start
 * tiny per-type validation PROBES against the next servers; the first VALID probe wins, every other
 * in-flight attempt (the original included, while it still has no headers) is cancelled, and the real
 * request is issued ONCE, to the winner. A winner's real request that then fails continues the race over
 * the remaining servers. Cancelled losers never touch the breaker, stats, `mainRetryAfter` or any error.
 *
 * TV only: an Xtream catalog answer may come from OkHttp's disk cache ([ServedFrom]). A fresh cache hit
 * wins like any answer but teaches nothing (no state written); a STALE copy served because the host
 * failed is held unread as the last resort while the race goes on, and is used only if every attempt
 * fails (the 7-day stale-catalog rule).
 */
@Singleton
class PlaylistServerFailover(
    private val store: ServerFailoverStateStore,
    private val clock: () -> Long,
    private val profileId: () -> Int,
    /** Monotonic ms the race scheduler runs on. Tests pass virtual time. */
    private val raceClock: () -> Long = monotonicClock(),
) {

    @Inject
    constructor(store: PrefsServerFailoverStateStore, profileManager: ProfileManager) :
        this(store, { System.currentTimeMillis() }, { profileManager.activeProfileId.value })

    private val _version = MutableStateFlow(0L)
    /** Bumps on every state change, so state holders can re-read [activeIndexes]. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** playlist key -> URL of the server whose error the last failed walk surfaced (in memory; cleared by the next success). */
    private val lastFailedServers = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The server whose failure ended [acc]'s most recent walk — the one an error card should NAME. With
     * every server down that is the main server (its error is the one surfaced); when a backup refused
     * outright (a 401 behind a hung main) it is that backup. Null = the last walk succeeded, or none ran.
     */
    fun lastFailedServerUrl(acc: XtreamAccount): String? = lastFailedServers.value[acc.id]

    /** Main first, then the backups in priority order. An M3U file has no servers to walk. */
    fun servers(acc: XtreamAccount): List<String> = serversOf(acc)

    /** 0 = main; i = backup i. Always a valid index for [acc]'s current list. */
    fun activeIndex(acc: XtreamAccount): Int {
        val servers = servers(acc)
        if (servers.size <= 1) return 0
        return ServerFailoverPolicy.clamp(store.read(profileId(), acc.id), servers.size).activeIndex
    }

    /** [acc] as seen on its active server — what stream/catch-up/create_link URLs are built from. */
    fun activeAccount(acc: XtreamAccount): XtreamAccount {
        val servers = servers(acc)
        if (servers.size <= 1) return acc
        return onServer(acc, servers, activeIndex(acc))
    }

    /** Active index per playlist key for [accounts], only for those NOT on their main server. */
    fun activeIndexes(accounts: List<XtreamAccount>): Map<String, Int> =
        accounts.mapNotNull { acc -> activeIndex(acc).takeIf { it > 0 }?.let { acc.id to it } }.toMap()

    /**
     * Moves an Xtream stream URL built on any of [acc]'s servers onto the active one (a catalog fetched
     * earlier may have embedded the server that answered then). Anything else is returned unchanged:
     * M3U stream URLs are whatever the playlist lines say, and Stalker resolves create_link fresh.
     */
    fun rebaseStreamUrl(acc: XtreamAccount, url: String): String {
        if (acc.sourceType != XtreamAccount.SOURCE_XTREAM || acc.backupUrls.isNullOrEmpty()) return url
        val servers = servers(acc).map { it.trimEnd('/') }
        val active = servers[activeIndex(acc)]
        val from = servers.firstOrNull { url.startsWith("$it/") } ?: return url
        return if (from == active) url else active + url.substring(from.length)
    }

    /**
     * Runs one fail-over-able request: [attempt] is called with [acc] re-pointed at a server of the
     * playlist. A failure the classifier says a backup would repeat (401/403, 456, auth rejected, …) is
     * thrown at once. When every server fails, the MAIN server's error is thrown (the user's primary)
     * and the state is kept — unless a stale copy stood in for a dead host, which is then returned.
     *
     * [probe] is the tiny per-type validation request (Xtream login JSON, M3U `Range: 0-1023`, Stalker
     * handshake) the race runs against the backups; it returns normally for a VALID server and throws
     * [FailoverInvalidResponseException] / [FailoverAuthRejectedException] / a transport failure
     * otherwise. With no [probe] there is nothing safe to race (a real request is not something to
     * duplicate), so the servers are walked one at a time.
     *
     * [canRetry] is asked before any move after a request failed: a streamed body that already
     * delivered rows to its sink must not be replayed from another server (it would duplicate or
     * splice the catalog). [evidence] says whether an answer really came from the host (see [ServedFrom]).
     *
     * A racing [attempt] reports "I have a 2xx" through the transport's header signal
     * ([HeadersSignalInterceptor] on a [forFailoverAttempt] client, or [signalHttpHeaders]); an attempt
     * that never signals counts as answering when it completes.
     */
    suspend fun <T> run(
        acc: XtreamAccount,
        canRetry: () -> Boolean = { true },
        evidence: (T) -> ServedFrom = { ServedFrom.NETWORK },
        probe: (suspend (XtreamAccount) -> Unit)? = null,
        attempt: suspend (XtreamAccount) -> T,
    ): T {
        val servers = servers(acc)
        if (servers.size <= 1) return attempt(acc)
        val pid = profileId()
        val wallMs = clock()
        val state = ServerFailoverPolicy.clamp(store.read(pid, acc.id), servers.size)
        val order = ServerFailoverPolicy.order(state, servers.size, wallMs)
        val walk = Walk(acc, servers, pid, state, wallMs, canRetry, evidence, probe, attempt)
        return if (probe == null) walk.sequential(order) else walk.race(order)
    }

    /** One call of [run]: its inputs, and what the walk has learned so far. */
    private inner class Walk<T>(
        val acc: XtreamAccount,
        val servers: List<String>,
        val pid: Int,
        val state: ServerFailoverState,
        val wallMs: Long,
        val canRetry: () -> Boolean,
        val evidence: (T) -> ServedFrom,
        val probe: (suspend (XtreamAccount) -> Unit)?,
        val attempt: suspend (XtreamAccount) -> T,
    ) {
        /** Fail-over-able failures by server index (never a cancelled loser). */
        val failures = LinkedHashMap<Int, Throwable>()

        /** A stale catalog copy served because its host FAILED — held unread, the last resort. */
        var staleCopy: Held<T>? = null

        fun accFor(index: Int): XtreamAccount = onServer(acc, servers, index)

        /** The real request on [index] alone, with the failover connect timeout and no racing. */
        suspend fun real(index: Int): T =
            withContext(HttpAttemptSignal(FailoverRace.CONNECT_TIMEOUT_MS)) { attempt(accFor(index)) }

        /** [index]'s host failed and a stale copy stood in: keep the first one, count the host as failed. */
        fun holdStale(index: Int, value: T) {
            if (staleCopy == null) staleCopy = Held(value)
            failures[index] = StaleCopyServedException(servers[index])
        }

        /**
         * [error] is the walk's result. A stale copy already in hand beats an answer every server would
         * repeat; otherwise it is thrown, remembering which server it came from for the error card.
         */
        fun surface(index: Int, error: Throwable): T {
            finish(null, 0L, served = staleCopy != null)
            staleCopy?.let { return it.value }
            lastFailedServers.update { it + (acc.id to servers[index]) }
            throw error
        }

        /** Every server failed: the stale copy if one stood in, else the main server's error (else the last one's). */
        fun giveUp(surface: Int): T {
            val index = when {
                surface in failures -> surface
                0 in failures -> 0
                else -> failures.keys.lastOrNull() ?: 0
            }
            return surface(index, failures[index] ?: IllegalStateException("No server answered for ${acc.name}"))
        }

        /** The walk's end: record what it learned (state + stats), unless the caller cancelled. */
        fun finish(successServer: Int?, sampleMs: Long, served: Boolean = successServer != null) =
            finishWalk(this, successServer, sampleMs, served)

        /** [error] ended [index]'s turn: fail over from it (true) or surface it (false). */
        fun failsOver(error: Throwable): Boolean =
            FailoverFailureClassifier.shouldFailOver(classifyFailoverThrowable(error)) && canRetry()

        // --- no probe: the original one-at-a-time walk (no race, no time budget) -----------------

        suspend fun sequential(order: List<Int>): T {
            val started = raceClock()
            for (index in order) {
                val result = try {
                    real(index)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (!failsOver(t)) return surface(index, t)
                    failures[index] = t
                    continue
                }
                when (evidence(result)) {
                    ServedFrom.NETWORK -> { finish(index, raceClock() - started); return result }
                    // Never touched the host: nothing learned, nothing more to try.
                    ServedFrom.CACHE -> { finish(null, 0L, served = true); return result }
                    // The host failed and a stale copy stood in: keep walking, keep the copy as last resort.
                    ServedFrom.STALE_FALLBACK -> {
                        holdStale(index, result)
                        if (!canRetry()) return giveUp(0)
                    }
                }
            }
            return giveUp(0)
        }

        // --- probe: staggered race -------------------------------------------------------------

        suspend fun race(order: List<Int>): T {
            var candidates = order
            var realFirst = true
            while (true) {
                when (val round = raceRound(candidates, realFirst)) {
                    is Round.RealWon -> {
                        if (round.servedFrom == ServedFrom.CACHE) finish(null, 0L, served = true)
                        else finish(round.server, round.timeMs)
                        return round.value
                    }
                    is Round.Surface -> return surface(round.server, round.error)
                    is Round.GaveUp -> return giveUp(round.surface)
                    is Round.ProbeWon -> {
                        val value = try {
                            real(round.server)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            if (!failsOver(t)) return surface(round.server, t)
                            failures[round.server] = t
                            candidates = candidates.filter { it !in failures.keys }
                            if (candidates.isEmpty()) return giveUp(0)
                            realFirst = false
                            continue
                        }
                        if (evidence(value) == ServedFrom.STALE_FALLBACK) {
                            holdStale(round.server, value)
                            candidates = candidates.filter { it !in failures.keys }
                            if (candidates.isEmpty() || !canRetry()) return giveUp(0)
                            realFirst = false
                            continue
                        }
                        finish(round.server, round.timeMs)
                        return value
                    }
                    is Round.HeldStale -> {
                        candidates = candidates.filter { it !in failures.keys }
                        if (candidates.isEmpty() || !canRetry()) return giveUp(0)
                        realFirst = false
                    }
                    is Round.WonThenFailed -> {
                        if (!failsOver(round.error)) return surface(round.server, round.error)
                        failures[round.server] = round.error
                        candidates = candidates.filter { it !in failures.keys }
                        if (candidates.isEmpty()) return giveUp(0)
                        realFirst = false
                    }
                }
            }
        }

        /**
         * One staggered race over [candidates]. With [realFirst] the first candidate runs the REAL
         * request (racing to its response headers); every other attempt is a [probe]. Returns once the
         * race is decided and every attempt of it has finished (losers cancelled, never read).
         */
        private suspend fun raceRound(candidates: List<Int>, realFirst: Boolean): Round<T> = coroutineScope {
            val events = Channel<Any>(Channel.UNLIMITED)
            val scheduler = FailoverRaceScheduler(
                order = candidates,
                hostOf = { FailoverHostKey.of(servers[it]) },
                staggerOf = { FailoverStagger.compute(state.stats[it], wallMs) },
            )
            val jobs = HashMap<Int, Job>()
            val startedAt = HashMap<Int, Long>()
            // A racing real request is parked on its gate at its response headers until the race is
            // decided: completed = it won (read the body), cancelled = it lost (never read the body).
            val gates = HashMap<Int, CompletableDeferred<Unit>>()
            val headersMs = HashMap<Int, Long>()
            var winner: Int? = null
            var outcome: Round<T>? = null
            val firstServer = candidates.first()

            fun launchAttempt(server: Int) {
                val isReal = realFirst && server == firstServer
                val gate = CompletableDeferred<Unit>().also { gates[server] = it }
                startedAt[server] = raceClock()
                jobs[server] = launch {
                    val signal = HttpAttemptSignal(
                        connectTimeoutMs = FailoverRace.CONNECT_TIMEOUT_MS,
                        onLocalWait = { waiting -> events.trySend(LocalWait(server, waiting)) },
                        onHeaders = if (isReal) ({ events.trySend(Headers(server)); gate.await() }) else null,
                    )
                    val done = try {
                        withContext(signal) {
                            if (isReal) Done(server, true, attempt(accFor(server)), null)
                            else { probe!!(accFor(server)); Done(server, false, null, null) }
                        }
                    } catch (t: Throwable) {
                        Done(server, isReal, null, t)
                    }
                    events.trySend(done)
                }
            }

            fun cancelAttempt(server: Int) {
                jobs[server]?.cancel()
                gates[server]?.cancel()   // a loser parked at its headers on a blocked transport thread
            }

            fun handle(decisions: List<RaceDecision>) {
                for (d in decisions) when (d) {
                    is RaceDecision.StartAttempt -> launchAttempt(d.server)
                    is RaceDecision.CancelAttempts -> d.servers.forEach(::cancelAttempt)
                    is RaceDecision.Winner -> winner = d.server
                    is RaceDecision.GiveUp -> outcome = Round.GaveUp(d.surfaceServer)
                }
            }

            try {
                handle(scheduler.onTick(raceClock()))
                var timer: Job? = null
                while (outcome == null) {
                    timer?.cancel()
                    val wake = if (winner == null) scheduler.nextWakeMs(raceClock()) else null
                    timer = wake?.let { at ->
                        launch { delay((at - raceClock()).coerceAtLeast(0L)); events.trySend(Tick) }
                    }
                    val event = events.receive()
                    val now = raceClock()
                    when (event) {
                        is Tick -> if (winner == null) handle(scheduler.onTick(now))
                        is LocalWait -> if (winner == null) handle(scheduler.onLocalWait(now, event.server, event.waiting))
                        is Headers -> if (winner == null) {
                            headersMs[event.server] = now - (startedAt[event.server] ?: now)
                            handle(scheduler.onHeaders(now, event.server))
                            if (winner == event.server) gates[event.server]?.complete(Unit)
                        }
                        is Done -> {
                            val done = event
                            val server = done.server
                            val error = done.error
                            @Suppress("UNCHECKED_CAST")
                            val value = done.value as T
                            when {
                                error is CancellationException -> if (winner == null) handle(scheduler.onCancelled(now, server))
                                error == null && winner == null -> {
                                    // A valid probe, or a real request that finished without ever signalling.
                                    val ms = now - (startedAt[server] ?: now)
                                    if (!done.isReal) {
                                        handle(scheduler.onHeaders(now, server))
                                        outcome = Round.ProbeWon(server, ms)
                                    } else when (val from = evidence(value)) {
                                        ServedFrom.STALE_FALLBACK -> {
                                            // The host failed; its stale copy waits unread while the others race.
                                            holdStale(server, value)
                                            if (!canRetry()) outcome = Round.GaveUp(server)
                                            else handle(scheduler.onFailed(now, server, true))
                                        }
                                        else -> {
                                            handle(scheduler.onHeaders(now, server))
                                            outcome = Round.RealWon(server, value, ms, from)
                                        }
                                    }
                                }
                                error == null && winner == server && done.isReal -> {
                                    val from = evidence(value)
                                    outcome = if (from == ServedFrom.STALE_FALLBACK) {
                                        holdStale(server, value)   // defensive: a stand-in never really "won"
                                        Round.HeldStale(server)
                                    } else {
                                        Round.RealWon(server, value, headersMs[server] ?: (now - (startedAt[server] ?: now)), from)
                                    }
                                }
                                error == null -> Unit   // a cancelled loser that finished anyway: ignored, never read
                                winner == server -> outcome = Round.WonThenFailed(server, error)
                                winner != null -> Unit  // a loser's late failure caused by its own cancellation
                                failsOver(error) -> {
                                    failures[server] = error
                                    handle(scheduler.onFailed(now, server, true))
                                }
                                else -> {
                                    handle(scheduler.onFailed(now, server, false))
                                    outcome = Round.Surface(server, error)
                                }
                            }
                        }
                    }
                }
                timer?.cancel()
            } finally {
                // Every attempt of this round ends here: losers cancelled, parked transports released.
                jobs.keys.forEach(::cancelAttempt)
            }
            outcome!!
        }
    }

    private object Tick
    private class Done(val server: Int, val isReal: Boolean, val value: Any?, val error: Throwable?)
    private class Headers(val server: Int)
    private class LocalWait(val server: Int, val waiting: Boolean)

    private sealed interface Round<out V> {
        class RealWon<V>(val server: Int, val value: V, val timeMs: Long, val servedFrom: ServedFrom) : Round<V>
        class ProbeWon(val server: Int, val timeMs: Long) : Round<Nothing>
        class Surface(val server: Int, val error: Throwable) : Round<Nothing>
        class GaveUp(val surface: Int) : Round<Nothing>
        class WonThenFailed(val server: Int, val error: Throwable) : Round<Nothing>
        class HeldStale(val server: Int) : Round<Nothing>
    }

    /**
     * Records what a finished walk learned. [served] = the request was answered (a success, a fresh
     * cache hit, or a stale stand-in): the error card's "failed host" is cleared. Only a NETWORK success
     * ([successServer]) moves the active server and adds a latency sample; every fail-over-able failure
     * marks its server (never a cancelled loser — those are not in [Walk.failures]).
     */
    private fun finishWalk(w: Walk<*>, successServer: Int?, sampleMs: Long, served: Boolean) {
        if (served && w.acc.id in lastFailedServers.value) lastFailedServers.update { it - w.acc.id }
        val now = clock()
        val n = w.servers.size
        record(w.pid, w.acc.id, n) { cur ->
            val next = if (successServer != null) ServerFailoverPolicy.onSuccess(cur, successServer, now, n)
            else ServerFailoverPolicy.onAllFailed(cur)
            var stats = next.stats
            for (failed in w.failures.keys) stats = stats + (failed to FailoverLatencyStats.onFailure(stats[failed], now))
            if (successServer != null) {
                val updated = FailoverLatencyStats.onWin(stats[successServer], sampleMs, now)
                stats = if (updated == null) stats - successServer else stats + (successServer to updated)
            }
            stats = stats.mapNotNull { (k, v) -> FailoverLatencyStats.prune(v, now)?.let { k to it } }.toMap()
            next.copy(stats = stats)
        }
    }

    /** The user edited [playlistKey]'s server list: back to the main server, no window. */
    fun reset(playlistKey: String) {
        lastFailedServers.update { it - playlistKey }
        record(profileId(), playlistKey, Int.MAX_VALUE) { ServerFailoverState() }
    }

    /** A local edit saved [new] over [old]: a changed server list (main or backups) starts on main. */
    fun onPlaylistEdited(old: XtreamAccount, new: XtreamAccount) {
        if (serverListChanged(old, new)) reset(old.id)
    }

    /** A sync pull replaced [before] with [after]: a server list changed elsewhere restarts on main here. */
    fun onPulled(before: List<XtreamAccount>, after: List<XtreamAccount>) {
        val priorById = before.associateBy { it.id }
        for (acc in after) {
            val prior = priorById[acc.id] ?: continue
            if (serverListChanged(prior, acc)) reset(acc.id)
        }
    }

    /** PlaylistRemovalCleanup's ServerFailover target: drop [playlistKey]'s state for [profileId]. */
    fun forget(profileId: Int, playlistKey: String) {
        lastFailedServers.update { it - playlistKey }
        if (store.read(profileId, playlistKey) == ServerFailoverState()) return
        store.clear(profileId, playlistKey)
        _version.update { it + 1 }
    }

    /** Walks of one playlist finish concurrently (a hub fans out many catalog calls): read-modify-write must not interleave. */
    private val recordLock = Any()

    private fun record(pid: Int, key: String, serverCount: Int, next: (ServerFailoverState) -> ServerFailoverState) {
        val visibleChange = synchronized(recordLock) {
            val current = store.read(pid, key)
            val updated = next(ServerFailoverPolicy.clamp(current, serverCount))
            if (updated == current) return
            store.write(pid, key, updated)
            // Stats-only changes are invisible to the UI: no state holder needs to re-read the active index.
            updated.activeIndex != current.activeIndex || updated.mainRetryAfterMs != current.mainRetryAfterMs
        }
        if (visibleChange) _version.update { it + 1 }
    }

    private class Held<T>(val value: T)

    companion object {
        /** Main first, then the backups in priority order. An M3U file has no servers to walk. */
        fun serversOf(acc: XtreamAccount): List<String> =
            if (BackupServerValidation.supportsBackups(acc.sourceType)) listOf(mainServer(acc)) + acc.backupUrls.orEmpty()
            else listOf(mainServer(acc))

        /** The playlist's main server: the base URL (Xtream / M3U link) or the portal (Stalker). */
        fun mainServer(acc: XtreamAccount): String =
            if (acc.sourceType == XtreamAccount.SOURCE_STALKER) acc.portalUrl.ifBlank { acc.baseUrl } else acc.baseUrl

        /** Whether an edit/pull changed which servers a playlist is reached on (main or backups). */
        fun serverListChanged(old: XtreamAccount, new: XtreamAccount): Boolean =
            mainServer(old) != mainServer(new) || old.backupUrls.orEmpty() != new.backupUrls.orEmpty()

        /** A detached instance (in-memory, wall clock, profile 1) — the default for hand-built test graphs. */
        fun detached(): PlaylistServerFailover =
            PlaylistServerFailover(InMemoryServerFailoverStateStore(), { System.currentTimeMillis() }, { 1 })

        private fun monotonicClock(): () -> Long {
            val epoch = TimeSource.Monotonic.markNow()
            return { epoch.elapsedNow().inWholeMilliseconds }
        }

        private fun onServer(acc: XtreamAccount, servers: List<String>, index: Int): XtreamAccount {
            if (index == 0) return acc
            val url = servers[index]
            return if (acc.sourceType == XtreamAccount.SOURCE_STALKER) acc.copy(portalUrl = url, baseUrl = url)
            else acc.copy(baseUrl = url)
        }
    }
}

/**
 * Marks a server whose request was answered only by a stale cached copy (TV's catalog fallback): the
 * host itself failed. Never thrown to a caller — the copy is returned instead when nothing better came.
 */
internal class StaleCopyServedException(server: String) : IOException("$server failed; a stale cached copy stood in")

/**
 * One throwable from a fail-over-able request, in [FailoverFailureKind] terms (OkHttp / java.net),
 * walking the cause chain because stacks wrap them. Same mapping as NuvioMobile's Android actual.
 */
internal fun classifyFailoverThrowable(t: Throwable): FailoverFailure {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 6) {
        when (cur) {
            is CancellationException -> return FailoverFailure(FailoverFailureKind.CANCELLED)
            is HttpStatusException -> return FailoverFailure(FailoverFailureKind.HTTP_STATUS, cur.status)
            is FailoverInvalidResponseException -> return FailoverFailure(FailoverFailureKind.INVALID_RESPONSE)
            is FailoverAuthRejectedException -> return FailoverFailure(FailoverFailureKind.AUTH_REJECTED)
            is PanelHostFastFailException, is PanelHostFastFailIOException ->
                return FailoverFailure(FailoverFailureKind.HOST_UNAVAILABLE)
            is UnknownHostException -> return FailoverFailure(FailoverFailureKind.DNS)
            is ConnectException, is NoRouteToHostException, is PortUnreachableException ->
                return FailoverFailure(FailoverFailureKind.CONNECT_REFUSED)
            // OkHttp reports connect and read timeouts both as SocketTimeoutException; its connect
            // variant says so in the message.
            is SocketTimeoutException -> return FailoverFailure(
                if (cur.message?.contains("connect", ignoreCase = true) == true) FailoverFailureKind.CONNECT_TIMEOUT
                else FailoverFailureKind.READ_TIMEOUT
            )
            is SSLHandshakeException, is SSLPeerUnverifiedException -> return FailoverFailure(FailoverFailureKind.TLS_HANDSHAKE)
            // ECONNRESET, truncated bodies, OkHttp call cancel: proves nothing about the host — keep walking the chain.
            is IOException -> Unit
        }
        cur = cur.cause?.takeIf { it !== cur }
        depth++
    }
    return FailoverFailure(FailoverFailureKind.OTHER)
}
