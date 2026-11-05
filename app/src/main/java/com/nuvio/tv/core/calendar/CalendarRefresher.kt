// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import android.content.res.Resources
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CalendarClock

enum class CalendarRefreshReason { CALENDAR_OPENED, HOME_RESUMED, RETRY, SETTINGS_CHANGED }

data class CalendarState(
    val profileId: Int? = null,
    /** Followed titles with their releases, hidden titles left out. Empty until [loaded]. */
    val titles: List<CalendarTitleRecord> = emptyList(),
    /** The cache and the profile's settings are both read. */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val remaining: Int = 0,
    /** Goes up each time a title finishes during a run; the screen's stall deadline restarts on it. */
    val progressTicks: Long = 0L,
    val seenReminders: Set<String> = emptySet(),
    /** Watched episodes as last read by a refresh; null until one has run for this profile. */
    val watched: CalendarWatchedIndex? = null,
    /** The last finished run in this process for this profile; null when none has finished. */
    val lastRun: CalendarRunResult? = null
)

/** What the refresher reads from the rest of the app. */
interface CalendarRefreshInputs {
    val activeProfileId: StateFlow<Int>
    val settings: StateFlow<CalendarSettings?>
    suspend fun settings(profileId: Int): CalendarSettings
    suspend fun followed(profileId: Int): CalendarFollowedSnapshot
    fun traktSignedIn(): Boolean
    fun region(): String
    /** The resolved Calendar zone (override, else the box's). */
    fun zone(): ZoneId
}

@Singleton
class DefaultCalendarRefreshInputs @Inject constructor(
    private val settingsStore: CalendarSettingsDataStore,
    private val sourceReader: CalendarSourceReader,
    private val trackingProviders: TrackingProviderRegistry,
    profileManager: ProfileManager
) : CalendarRefreshInputs {
    override val activeProfileId = profileManager.activeProfileId
    override val settings = settingsStore.settings
    override suspend fun settings(profileId: Int) = settingsStore.current(profileId)
    override suspend fun followed(profileId: Int) = sourceReader.read(profileId)
    override fun traktSignedIn() = trackingProviders.isAuthenticated(TrackingProviderId.TRAKT)
    override fun zone(): ZoneId = settingsStore.settings.value.resolvedZone()

    /** The box's own region, not the app language, for film release dates. */
    override fun region(): String =
        Resources.getSystem().configuration.locales[0]?.country?.takeIf { it.length == 2 } ?: "US"
}

/**
 * Keeps the Calendar of the active profile. A run reads the followed titles from what the app already has, asks the
 * Trakt calendar when signed in and due, then refreshes only the titles that are due, [concurrency] at a time. Every
 * finished title is kept, so a cancelled run carries on where it stopped next time. A request while a run is going
 * is kept and runs once after it. An idle slate (nothing due, Trakt fresh) makes no request at all.
 *
 * There is no timer here: runs happen on an event (Calendar opened, Home resumed, Retry, a setting changed), each
 * gated by [CalendarRefreshGate] at the caller.
 */
@Singleton
class CalendarRefresher internal constructor(
    private val storage: CalendarStorage,
    private val inputs: CalendarRefreshInputs,
    private val remote: CalendarRemoteSources,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val concurrency: Int = DEFAULT_CONCURRENCY
) {
    @Inject
    constructor(
        storage: CalendarStorage,
        inputs: CalendarRefreshInputs,
        remote: CalendarRemoteSources,
        @CalendarClock clock: Clock
    ) : this(storage, inputs, remote, clock, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** The profile and storage generation a run started with; anything it writes after either changed is dropped. */
    private data class Owner(val profileId: Int, val generation: Int)

    private val started = AtomicBoolean(false)
    private val dataMutex = Mutex()
    private val jobLock = Any()
    private var job: Job? = null
    private var jobTakesRequests = false
    private var runAgain: CalendarRefreshReason? = null

    private var data = CalendarSnapshot()
    private var owner: Owner? = null
    private var watched: CalendarWatchedIndex? = null
    private var lastRun: CalendarRunResult? = null
    private var progressTicks = 0L

    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    /** Starts a run now (or once after the current one). Callers gate it with [CalendarRefreshGate]. */
    fun requestRefresh(reason: CalendarRefreshReason) {
        ensureStarted()
        synchronized(jobLock) {
            val previous = job
            if (previous?.isActive == true && jobTakesRequests) {
                if (runAgain == null || reason == CalendarRefreshReason.SETTINGS_CHANGED) runAgain = reason
                return
            }
            jobTakesRequests = true
            job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                val me = currentCoroutineContext()[Job]
                var next: CalendarRefreshReason? = reason
                while (next != null) {
                    runRefresh(next)
                    next = synchronized(jobLock) {
                        if (job !== me) return@synchronized null
                        runAgain?.also { runAgain = null } ?: run {
                            jobTakesRequests = false
                            null
                        }
                    }
                }
            }.also { it.start() }
        }
    }

    /** Reads the cached Calendar without asking for anything new. */
    fun start() = ensureStarted()

    fun cancelRefresh() {
        synchronized(jobLock) {
            runAgain = null
            job?.cancel()
        }
    }

    suspend fun dueReminders(): CalendarDueReminders {
        ensureStarted()
        val current = ensureLoaded(inputs.activeProfileId.value)
        val settings = inputs.settings(current.profileId)
        val index = watchedIndex(current)
        val (titles, seen) = dataMutex.withLock { data.titles.visible(settings) to data.seenReminders.keys }
        return dueReminders(titles, settings.bellOn, seen, index::isWatched, clock.instant())
    }

    suspend fun markRemindersSeen(episodeKeys: Collection<String>) {
        if (episodeKeys.isEmpty()) return
        ensureStarted()
        val current = ensureLoaded(inputs.activeProfileId.value)
        withOwner(current) {
            data = data.copy(seenReminders = data.seenReminders.withSeen(episodeKeys, clock.instant()).prunedSeen(clock.instant()))
            publishLocked()
        }
        save(current, force = true)
    }

    private fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var previous: Pair<Int, Int>? = null
            combine(inputs.activeProfileId, storage.generations) { profileId, generation -> profileId to generation }
                .distinctUntilChanged()
                .collect { key ->
                    if (previous != null) cancelRefresh()
                    previous = key
                    ensureLoaded(key.first)
                }
        }
        scope.launch {
            inputs.settings.collect { dataMutex.withLock { publishLocked() } }
        }
    }

    private suspend fun runRefresh(reason: CalendarRefreshReason) {
        val me = currentCoroutineContext()[Job]
        val current = ensureLoaded(inputs.activeProfileId.value)
        setRefreshing(current, me, refreshing = true, remaining = 0)
        val attempted = AtomicInteger(0)
        val succeeded = AtomicInteger(0)
        var finished = false
        try {
            val profileId = current.profileId
            val settings = inputs.settings(profileId)
            val now = clock.instant()
            val zone = inputs.zone()
            val sources = try {
                inputs.followed(profileId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "sources unreadable: ${error.message}")
                CalendarFollowedSnapshot(unreadOrigins = CalendarOrigin.entries.toSet() - CalendarOrigin.BELL)
            }
            withOwner(current) {
                watched = sources.watched
                publishLocked()
            } ?: return

            val traktSignedIn = inputs.traktSignedIn()
            val snapshot = dataMutex.withLock { data }
            val traktItems = if (traktSignedIn && calendarTraktCalendarDue(snapshot.traktCalendarFetchedAtMs, now)) {
                attempted.incrementAndGet()
                remote.traktMyShows(now.atZone(ZoneOffset.UTC).toLocalDate())?.also { succeeded.incrementAndGet() }
            } else {
                null
            }
            val traktCandidates = when {
                !traktSignedIn -> emptyList()
                traktItems != null -> traktCalendarCandidates(traktItems)
                else -> snapshot.titles.filter { CalendarOrigin.TRAKT_CALENDAR in it.origins }.map {
                    CalendarTitleCandidate(it.ids, it.type, CalendarOrigin.TRAKT_CALENDAR, name = it.name)
                }
            }
            val rules = CalendarFollowRules(hiddenTitleKeys = settings.hiddenTitleKeys, bellOn = settings.bellOn)
            val carried = carriedCalendarCandidates(snapshot.titles, sources.unreadOrigins)
            val followed = followCalendarTitles(sources.candidates + traktCandidates + carried, rules, now)

            val due = withOwner(current) {
                var records = reconcileCalendarRecords(followed, data.titles)
                records = when {
                    !traktSignedIn -> records.map { it.dueWithoutTraktCalendar() }
                    traktItems != null -> applyTraktCalendar(records, traktItems, now)
                    else -> records
                }
                data = data.copy(
                    titles = records,
                    traktCalendarFetchedAtMs = when {
                        traktItems != null -> now.toEpochMilli()
                        traktSignedIn -> data.traktCalendarFetchedAtMs
                        else -> 0L
                    }
                )
                publishLocked()
                records.filter { it.isDue(now) }
                    .sortedWith(compareByDescending<CalendarTitleRecord> { CalendarOrigin.BELL in it.origins }.thenBy { it.nextRefreshAtMs })
            } ?: return
            save(current, force = true)
            Log.i(TAG, "refresh reason=$reason titles=${followed.size} due=${due.size} trakt=${traktItems?.size ?: "-"}")
            if (due.isNotEmpty()) {
                setRefreshing(current, me, refreshing = true, remaining = due.size)
                val fetcher = CalendarTitleFetcher(remote, inputs.region(), zone)
                val queue = ArrayDeque(due.map { it.key })
                supervisorScope {
                    repeat(concurrency.coerceAtLeast(1)) {
                        launch {
                            while (true) {
                                val key = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                                val outcome = refreshOne(current, key, fetcher) ?: continue
                                if (outcome.attempted) {
                                    attempted.incrementAndGet()
                                    if (!outcome.failed) succeeded.incrementAndGet()
                                }
                            }
                        }
                    }
                }
            }
            finished = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Calendar refresh stopped: ${error.message}")
            finished = true
        } finally {
            withContext(NonCancellable) {
                if (finished) {
                    val result = CalendarRunResult(clock.millis(), attempted.get(), succeeded.get())
                    dataMutex.withLock { if (owner == current) lastRun = result }
                    if (result.failed) Log.w(TAG, "run failed: ${result.attempted} fetches, none answered")
                }
                save(current, force = true)
                setRefreshing(current, me, refreshing = false, remaining = 0)
            }
        }
    }

    private suspend fun refreshOne(current: Owner, key: String, fetcher: CalendarTitleFetcher): CalendarFetchOutcome? {
        val (record, tvMazeIds, traktFetchedAt) = withOwner(current) {
            Triple(data.titles.firstOrNull { it.key == key }, data.tvmazeIds, data.traktCalendarFetchedAtMs)
        } ?: return null
        if (record == null) return null
        val now = clock.instant()
        val signedIn = inputs.traktSignedIn()
        val outcome = try {
            fetcher.refresh(record, tvMazeIds, signedIn && record.isCoveredByTrakt(traktFetchedAt, now), now)
                .withoutTraktCalendarIfSignedOut(signedIn)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CalendarFetchOutcome(fetcher.failed(record, now), failed = true)
        }
        withOwner(current) {
            data = data.copy(
                titles = data.titles.map { if (it.key == key) outcome.record else it },
                tvmazeIds = data.tvmazeIds + outcome.tvMazeIds
            )
            progressTicks++
            publishLocked(remaining = (_state.value.remaining - 1).coerceAtLeast(0))
        }
        save(current, force = false)
        return outcome
    }

    private suspend fun ensureLoaded(profileId: Int): Owner = dataMutex.withLock {
        val wanted = Owner(profileId, storage.generation())
        if (owner != wanted) {
            data = storage.load(profileId)
            owner = wanted
            watched = null
            lastRun = null
            publishLocked(refreshing = false, remaining = 0)
        }
        wanted
    }

    /** Runs [block] under the lock only while [current] still owns the data; null otherwise. */
    private suspend inline fun <T> withOwner(current: Owner, block: () -> T): T? = dataMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (owner != current) null else block()
    }

    private suspend fun watchedIndex(current: Owner): CalendarWatchedIndex {
        dataMutex.withLock { if (owner == current) watched?.let { return it } }
        val index = runCatching { inputs.followed(current.profileId).watched }.getOrElse { CalendarWatchedIndex() }
        withOwner(current) {
            watched = index
            publishLocked()
        }
        return index
    }

    private suspend fun save(current: Owner, force: Boolean) {
        val snapshot = dataMutex.withLock { if (owner == current) data else null } ?: return
        storage.save(current.profileId, snapshot, current.generation, force)
    }

    /** Only the run that is current may change the refreshing flag. */
    private suspend fun setRefreshing(current: Owner, me: Job?, refreshing: Boolean, remaining: Int) {
        if (synchronized(jobLock) { job !== me }) return
        dataMutex.withLock { if (owner == current) publishLocked(refreshing = refreshing, remaining = remaining) }
    }

    private fun List<CalendarTitleRecord>.visible(settings: CalendarSettings): List<CalendarTitleRecord> {
        val hidden = settings.hiddenTitleKeys
        return if (hidden.isEmpty()) this else filterNot { title -> hidden.any(title::matchesKey) }
    }

    private fun publishLocked(
        refreshing: Boolean = _state.value.refreshing,
        remaining: Int = _state.value.remaining
    ) {
        val profileId = owner?.profileId
        val settings = inputs.settings.value?.takeIf { profileId != null && it.profileId == profileId }
        _state.value = CalendarState(
            profileId = profileId,
            titles = settings?.let { data.titles.visible(it) }.orEmpty(),
            loaded = settings != null,
            refreshing = refreshing,
            remaining = remaining,
            progressTicks = progressTicks,
            seenReminders = data.seenReminders.keys,
            watched = watched,
            lastRun = lastRun
        )
    }

    internal companion object {
        const val TAG = "CalendarRefresher"
        /** Fixed, not device-tiered (named gap vs Arc): two titles at a time keeps budget boxes responsive. */
        const val DEFAULT_CONCURRENCY = 2
    }
}

/**
 * Signed out of Trakt: the times its calendar gave stay on show until TVmaze or Trakt's public next-episode answer
 * replaces them, so the title is asked again now instead of losing its times for hours.
 */
internal fun CalendarTitleRecord.dueWithoutTraktCalendar(): CalendarTitleRecord =
    if (sources[CalendarSource.TRAKT].isNullOrEmpty() || nextRefreshAtMs == 0L) this else copy(nextRefreshAtMs = 0L)

/** After a good fetch while signed out, the Trakt calendar's own times give way to what was just fetched. */
internal fun CalendarFetchOutcome.withoutTraktCalendarIfSignedOut(signedIn: Boolean): CalendarFetchOutcome =
    if (signedIn || failed) this else copy(record = record.withoutSources(CalendarSource.TRAKT))

