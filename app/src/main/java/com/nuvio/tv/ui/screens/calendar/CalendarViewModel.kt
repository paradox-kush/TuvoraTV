// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.calendar.CALENDAR_DEFAULT_WINDOW_DAYS
import com.nuvio.tv.core.calendar.CalendarClock
import com.nuvio.tv.core.calendar.CalendarFilter
import com.nuvio.tv.core.calendar.CalendarGameItem
import com.nuvio.tv.core.calendar.CalendarSportsPolicy
import com.nuvio.tv.core.calendar.CalendarSportsSource
import com.nuvio.tv.core.calendar.CALENDAR_ALL_FILTERS
import kotlinx.coroutines.flow.Flow
import com.nuvio.tv.core.calendar.CalendarLoad
import com.nuvio.tv.core.calendar.CalendarLoadStatus
import com.nuvio.tv.core.calendar.CalendarRefreshGate
import com.nuvio.tv.core.calendar.CalendarRefreshReason
import com.nuvio.tv.core.calendar.CalendarRefresher
import com.nuvio.tv.core.calendar.CalendarSettings
import com.nuvio.tv.core.calendar.CalendarSettingsDataStore
import com.nuvio.tv.core.calendar.CalendarState
import com.nuvio.tv.core.calendar.CalendarViewMode
import com.nuvio.tv.core.calendar.buildCalendarSections
import com.nuvio.tv.core.calendar.resolveCalendarZone
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Calendar screen reads and changes; the refresher and the settings behind one seam. */
interface CalendarScreenData {
    val state: StateFlow<CalendarState>
    val settings: StateFlow<CalendarSettings?>
    fun nowMs(): Long
    fun systemZone(): ZoneId
    fun requestRefresh(reason: CalendarRefreshReason)
    suspend fun setBell(titleKey: String, on: Boolean)
    suspend fun setHidden(titleKey: String)
    suspend fun unhideAll()
    suspend fun setWindowDays(days: Int)
    suspend fun setZoneOverride(zone: String?)
    suspend fun setCornerMessage(enabled: Boolean)
    suspend fun setComingUpRow(enabled: Boolean)
    suspend fun setViewMode(mode: CalendarViewMode)
    suspend fun setFilters(filters: Set<CalendarFilter>)
    /** Games already held by Sports (never a fetch) and when they changed. */
    suspend fun games(): List<CalendarGameItem>
    val gameChanges: Flow<Unit>
    fun watchGame(gameId: String)
}

class DefaultCalendarScreenData @Inject constructor(
    private val refresher: CalendarRefresher,
    private val settingsStore: CalendarSettingsDataStore,
    private val sports: CalendarSportsSource,
    @CalendarClock private val clock: Clock
) : CalendarScreenData {
    override suspend fun setFilters(filters: Set<CalendarFilter>) = settingsStore.setFilters(filters).let { }
    override suspend fun games(): List<CalendarGameItem> = sports.games()
    override val gameChanges: Flow<Unit> get() = sports.changes
    override fun watchGame(gameId: String) = sports.requestWatch(gameId)
    override val state get() = refresher.state.also { refresher.start() }
    override val settings get() = settingsStore.settings
    override fun nowMs(): Long = clock.millis()
    override fun systemZone(): ZoneId = ZoneId.systemDefault()
    override fun requestRefresh(reason: CalendarRefreshReason) = refresher.requestRefresh(reason)
    override suspend fun setBell(titleKey: String, on: Boolean) = settingsStore.setBell(titleKey, on).let { }
    override suspend fun setHidden(titleKey: String) = settingsStore.setTitleHidden(titleKey, hidden = true).let { }
    override suspend fun unhideAll() {
        settingsStore.unhideAll()
        refresher.requestRefresh(CalendarRefreshReason.SETTINGS_CHANGED)
    }
    override suspend fun setWindowDays(days: Int) = settingsStore.setWindowDays(days).let { }
    override suspend fun setZoneOverride(zone: String?) = settingsStore.setZoneOverride(zone).let { }
    override suspend fun setCornerMessage(enabled: Boolean) = settingsStore.setCornerMessage(enabled).let { }
    override suspend fun setComingUpRow(enabled: Boolean) = settingsStore.setComingUpRow(enabled).let { }
    override suspend fun setViewMode(mode: CalendarViewMode) = settingsStore.setViewMode(mode).let { }
}

@Immutable
data class CalendarUiState(
    val status: CalendarScreenStatus = CalendarScreenStatus.Loading(0),
    val zone: ZoneId = ZoneId.of("UTC"),
    val zoneOverride: String? = null,
    val today: LocalDate = LocalDate.EPOCH,
    val windowDays: Int = CALENDAR_DEFAULT_WINDOW_DAYS,
    val viewMode: CalendarViewMode = CalendarViewMode.LIST,
    val list: CalendarListModel = CalendarListModel(),
    val days: Map<LocalDate, List<CalendarRow>> = emptyMap(),
    val cornerMessage: Boolean = true,
    val comingUpRow: Boolean = false,
    val hiddenCount: Int = 0,
    val filters: Set<CalendarFilter> = CALENDAR_ALL_FILTERS
)

/**
 * Orchestration only: decisions live in [calendarScreenStatus], [CalendarRefreshGate] and [CalendarLoad]. The
 * stall deadline is armed here as one one-shot timer per deadline (never a polling loop), so the screen ends its
 * wait even if the refresh never returns.
 */
@HiltViewModel
class CalendarViewModel internal constructor(
    private val data: CalendarScreenData,
    private val nowMs: () -> Long,
    private val stallMs: Long
) : ViewModel() {

    @Inject
    constructor(data: CalendarScreenData) : this(data, data::nowMs, CalendarLoad.STALL_MS)

    private val load = MutableStateFlow<CalendarLoadStatus>(CalendarLoadStatus.Idle)
    /** Bumped when a deadline passes, so the status is derived again at that moment. */
    private val deadlineTick = MutableStateFlow(0)
    private var deadlineJob: Job? = null
    private var lastTicks = -1L

    private val _month = MutableStateFlow<YearMonth?>(null)
    /** The month on show in Month view; null for this month. */
    val month: StateFlow<YearMonth?> = _month.asStateFlow()

    /** Games as last read from what Sports holds; re-read on open and when Sports' data changed (no poll). */
    private val games = MutableStateFlow<List<CalendarGameItem>>(emptyList())

    val uiState: StateFlow<CalendarUiState> =
        combine(data.state, data.settings, load, deadlineTick, games) { state, settings, loadStatus, _, held ->
            build(state, settings, loadStatus, held)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CalendarUiState())

    init {
        viewModelScope.launch { data.state.collect(::trackLoad) }
        viewModelScope.launch { data.gameChanges.collect { reloadGames() } }
        reloadGames()
    }

    private fun reloadGames() {
        viewModelScope.launch { games.value = runCatching { data.games() }.getOrDefault(games.value) }
    }

    /** OK on "Watch now": hand the game to Sports' channel matching; the screen then navigates there. */
    fun watchGame(row: CalendarRow) {
        row.game?.let { data.watchGame(it.id) }
    }

    fun toggleFilter(filter: CalendarFilter) {
        val current = uiState.value.filters
        viewModelScope.launch { data.setFilters(CalendarSportsPolicy.toggle(current, filter)) }
    }

    /** Each time the page comes into view (ON_RESUME). Fix 3: refresh only when [CalendarRefreshGate] says so. */
    fun onScreenShown() {
        reloadGames()
        val state = data.state.value
        if (CalendarRefreshGate.onCalendarOpened(state.lastRun, state.refreshing, nowMs())) {
            data.requestRefresh(CalendarRefreshReason.CALENDAR_OPENED)
        }
    }

    /** Retry always runs and restarts the deadline. */
    fun retry() {
        arm(CalendarLoad.begin(nowMs(), stallMs))
        data.requestRefresh(CalendarRefreshReason.RETRY)
    }

    fun setViewMode(mode: CalendarViewMode) {
        viewModelScope.launch { data.setViewMode(mode) }
    }

    fun showMonth(month: YearMonth) {
        _month.value = month
    }

    fun toggleBell(row: CalendarRow) {
        if (!row.status.hasBell) return
        viewModelScope.launch { data.setBell(row.titleKey, row.status != CalendarRowStatus.BELL_ON) }
    }

    fun hide(row: CalendarRow) {
        viewModelScope.launch { data.setHidden(row.titleKey) }
    }

    fun unhideAll() {
        viewModelScope.launch { data.unhideAll() }
    }

    fun setWindowDays(days: Int) {
        viewModelScope.launch { data.setWindowDays(days) }
    }

    fun setZoneOverride(zone: String?) {
        viewModelScope.launch { data.setZoneOverride(zone) }
    }

    fun setCornerMessage(enabled: Boolean) {
        viewModelScope.launch { data.setCornerMessage(enabled) }
    }

    fun setComingUpRow(enabled: Boolean) {
        viewModelScope.launch { data.setComingUpRow(enabled) }
    }

    private fun trackLoad(state: CalendarState) {
        val waiting = !state.loaded || state.refreshing || state.lastRun == null
        val current = load.value
        when {
            !waiting -> {
                deadlineJob?.cancel()
                load.value = CalendarLoadStatus.Idle
            }
            current is CalendarLoadStatus.Idle -> arm(CalendarLoad.begin(nowMs(), stallMs))
            // Once the deadline has passed the screen stays Failed(timedOut) until the run ends or Retry.
            current is CalendarLoadStatus.Loading && state.progressTicks != lastTicks && lastTicks >= 0 &&
                CalendarLoad.effectiveAt(current, nowMs()) is CalendarLoadStatus.Loading ->
                arm(CalendarLoad.progressed(current, nowMs(), stallMs))
        }
        lastTicks = state.progressTicks
    }

    private fun arm(status: CalendarLoadStatus) {
        load.value = status
        deadlineJob?.cancel()
        if (status !is CalendarLoadStatus.Loading) return
        deadlineJob = viewModelScope.launch {
            delay((status.deadlineAtMs - nowMs()).coerceAtLeast(0L))
            deadlineTick.value++
        }
    }

    private fun build(
        state: CalendarState,
        settings: CalendarSettings?,
        loadStatus: CalendarLoadStatus,
        heldGames: List<CalendarGameItem>
    ): CalendarUiState {
        val filters = settings?.filters ?: CALENDAR_ALL_FILTERS
        val titles = state.titles.filter { CalendarSportsPolicy.keepsTitle(filters, it.type) }
        val shownGames = CalendarSportsPolicy.games(filters, heldGames)
        val now = Instant.ofEpochMilli(nowMs())
        val zone = resolveCalendarZone(settings?.zoneOverride, data.systemZone())
        val windowDays = settings?.windowDays ?: CALENDAR_DEFAULT_WINDOW_DAYS
        val bells = settings?.bellOn.orEmpty()
        val ready = state.loaded && settings != null
        val list = if (ready) {
            buildCalendarList(buildCalendarSections(titles, now, zone, windowDays, shownGames), now, bells, state.watched)
        } else CalendarListModel()
        val days = if (ready) buildCalendarDays(titles, now, zone, bells, state.watched, shownGames) else emptyMap()
        return CalendarUiState(
            status = calendarScreenStatus(state, settings != null, loadStatus, !list.isEmpty, now.toEpochMilli()),
            zone = zone,
            zoneOverride = settings?.zoneOverride,
            today = now.atZone(zone).toLocalDate(),
            windowDays = windowDays,
            viewMode = settings?.viewMode ?: CalendarViewMode.LIST,
            list = list,
            days = days,
            cornerMessage = settings?.cornerMessage ?: true,
            comingUpRow = settings?.comingUpRow ?: false,
            hiddenCount = settings?.hiddenTitleKeys?.size ?: 0,
            filters = filters
        )
    }
}
