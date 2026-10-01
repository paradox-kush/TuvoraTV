package com.nuvio.tv.core.iptv

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val gson = Gson()
    private val type = object : TypeToken<Map<String, ServerFailoverState>>() {}.type
    private val cache = ConcurrentHashMap<Int, Map<String, ServerFailoverState>>()

    private fun key(profileId: Int) = "profile_$profileId"

    private fun profile(profileId: Int): Map<String, ServerFailoverState> = cache.getOrPut(profileId) {
        runCatching {
            prefs.getString(key(profileId), null)
                ?.let { gson.fromJson<Map<String, ServerFailoverState>>(it, type) }
                // Gson bypasses defaults on a malformed row; drop anything that did not decode whole.
                ?.filterValues { it != null && it.activeIndex >= 0 }
        }.getOrNull().orEmpty()
    }

    override fun read(profileId: Int, playlistKey: String) = profile(profileId)[playlistKey] ?: ServerFailoverState()

    @Synchronized
    override fun write(profileId: Int, playlistKey: String, state: ServerFailoverState) {
        val current = profile(profileId)
        val next = if (state == ServerFailoverState()) current - playlistKey else current + (playlistKey to state)
        if (next == current) return
        cache[profileId] = next
        prefs.edit {
            if (next.isEmpty()) remove(key(profileId)) else putString(key(profileId), gson.toJson(next))
        }
    }

    override fun clear(profileId: Int, playlistKey: String) = write(profileId, playlistKey, ServerFailoverState())

    override fun all(profileId: Int): Map<String, ServerFailoverState> = profile(profileId)
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
 * Decisions are [ServerFailoverPolicy] (order/state) and [FailoverFailureClassifier] (which error
 * moves on); this class only executes them. A playlist with no backups takes the original
 * single-request path untouched — no state is read or written.
 */
@Singleton
class PlaylistServerFailover(
    private val store: ServerFailoverStateStore,
    private val clock: () -> Long,
    private val profileId: () -> Int,
) {

    @Inject
    constructor(store: PrefsServerFailoverStateStore, profileManager: ProfileManager) :
        this(store, { System.currentTimeMillis() }, { profileManager.activeProfileId.value })

    private val _version = MutableStateFlow(0L)
    /** Bumps on every state change, so state holders can re-read [activeIndexes]. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** Main first, then the backups in priority order. An M3U file has no servers to walk. */
    fun servers(acc: XtreamAccount): List<String> =
        if (BackupServerValidation.supportsBackups(acc.sourceType)) listOf(mainServer(acc)) + acc.backupUrls.orEmpty()
        else listOf(mainServer(acc))

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
     * Runs one fail-over-able request: [attempt] is called with [acc] re-pointed at each server in the
     * policy's order until one answers. A failure the classifier says a backup would repeat (401/403,
     * 456, auth=0, cancellation, …) is thrown at once. When every server fails, the MAIN server's error
     * is thrown (the user's primary) and the state is kept.
     *
     * [canRetry] is asked before moving on: a streamed body that already delivered rows to its sink
     * must not be replayed from another server (it would duplicate or splice the catalog).
     * [evidence] says whether an answer really came from the host (see [ServedFrom]).
     */
    suspend fun <T> run(
        acc: XtreamAccount,
        canRetry: () -> Boolean = { true },
        evidence: (T) -> ServedFrom = { ServedFrom.NETWORK },
        attempt: suspend (XtreamAccount) -> T,
    ): T {
        val servers = servers(acc)
        if (servers.size <= 1) return attempt(acc)
        val pid = profileId()
        val startMs = clock()
        val order = ServerFailoverPolicy.order(store.read(pid, acc.id), servers.size, startMs)
        val budgetMs = ServerFailoverPolicy.walkBudgetMs(SINGLE_REQUEST_TIMEOUT_MS, servers.size)
        var mainError: Throwable? = null
        var lastError: Throwable? = null
        var staleCopy: Held<T>? = null
        for ((position, index) in order.withIndex()) {
            if (position > 0) {
                if (!canRetry()) {
                    staleCopy?.let { return it.value }
                    throw lastError ?: IllegalStateException("No server answered for ${acc.name}")
                }
                if (!ServerFailoverPolicy.mayStartNextAttempt(clock() - startMs, budgetMs)) break
            }
            val result = try {
                attempt(onServer(acc, servers, index))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (!FailoverFailureClassifier.shouldFailOver(classifyFailoverThrowable(t))) {
                    // A stale copy already in hand beats an answer every server would repeat.
                    staleCopy?.let { return it.value }
                    throw t
                }
                if (index == 0) mainError = t
                lastError = t
                continue
            }
            when (evidence(result)) {
                ServedFrom.NETWORK -> {
                    record(pid, acc.id, servers.size) { ServerFailoverPolicy.onSuccess(it, index, clock(), servers.size) }
                    return result
                }
                // Never touched the host: nothing learned, nothing more to try.
                ServedFrom.CACHE -> return result
                // The host failed and a stale copy stood in: keep walking, keep the copy as last resort.
                ServedFrom.STALE_FALLBACK -> if (staleCopy == null) staleCopy = Held(result)
            }
        }
        staleCopy?.let { return it.value }
        throw mainError ?: lastError ?: IllegalStateException("No server answered for ${acc.name}")
    }

    /** The user edited [playlistKey]'s server list: back to the main server, no window. */
    fun reset(playlistKey: String) {
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
        if (store.read(profileId, playlistKey) == ServerFailoverState()) return
        store.clear(profileId, playlistKey)
        _version.update { it + 1 }
    }

    private fun record(pid: Int, key: String, serverCount: Int, next: (ServerFailoverState) -> ServerFailoverState) {
        val current = store.read(pid, key)
        val updated = next(ServerFailoverPolicy.clamp(current, serverCount))
        if (updated == current) return
        store.write(pid, key, updated)
        _version.update { it + 1 }
    }

    private class Held<T>(val value: T)

    companion object {
        /** The existing per-request read timeout of the Xtream/addon transport (NetworkModule: 60 s). */
        const val SINGLE_REQUEST_TIMEOUT_MS: Long = 60_000L

        /** The playlist's main server: the base URL (Xtream / M3U link) or the portal (Stalker). */
        fun mainServer(acc: XtreamAccount): String =
            if (acc.sourceType == XtreamAccount.SOURCE_STALKER) acc.portalUrl.ifBlank { acc.baseUrl } else acc.baseUrl

        /** Whether an edit/pull changed which servers a playlist is reached on (main or backups). */
        fun serverListChanged(old: XtreamAccount, new: XtreamAccount): Boolean =
            mainServer(old) != mainServer(new) || old.backupUrls.orEmpty() != new.backupUrls.orEmpty()

        /** A detached instance (in-memory, wall clock, profile 1) — the default for hand-built test graphs. */
        fun detached(): PlaylistServerFailover =
            PlaylistServerFailover(InMemoryServerFailoverStateStore(), { System.currentTimeMillis() }, { 1 })

        private fun onServer(acc: XtreamAccount, servers: List<String>, index: Int): XtreamAccount {
            if (index == 0) return acc
            val url = servers[index]
            return if (acc.sourceType == XtreamAccount.SOURCE_STALKER) acc.copy(portalUrl = url, baseUrl = url)
            else acc.copy(baseUrl = url)
        }
    }
}

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
