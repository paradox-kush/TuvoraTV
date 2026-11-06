// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import android.util.Log
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the reminders read from the Calendar. */
interface CalendarReminderData {
    val state: StateFlow<CalendarState>
    val settings: StateFlow<CalendarSettings?>
    suspend fun dueReminders(): CalendarDueReminders
    suspend fun markSeen(episodeKeys: Collection<String>)
    fun requestRefresh(reason: CalendarRefreshReason)
    fun nowMs(): Long
}

@Singleton
class DefaultCalendarReminderData @Inject constructor(
    private val refresher: CalendarRefresher,
    settingsStore: CalendarSettingsDataStore,
    @CalendarClock private val clock: Clock
) : CalendarReminderData {
    override val state: StateFlow<CalendarState> get() = refresher.state
    override val settings = settingsStore.settings
    override suspend fun dueReminders() = refresher.dueReminders()
    override suspend fun markSeen(episodeKeys: Collection<String>) = refresher.markRemindersSeen(episodeKeys)
    override fun requestRefresh(reason: CalendarRefreshReason) = refresher.requestRefresh(reason)
    override fun nowMs(): Long = clock.millis()
}

/**
 * The Home reminder card and the corner note. Nothing here runs on its own: Home calls [onHomeResumed]; the app's
 * foreground, lifecycle-bound loop calls [checkCorner] and sleeps for the delay it returns (1 min .. 6 h). Both
 * read only the stored Calendar; neither touches the network.
 */
@Singleton
class CalendarReminderCenter @Inject constructor(
    private val data: CalendarReminderData
) {
    private val _card = MutableStateFlow<CalendarReminderCard?>(null)
    val card: StateFlow<CalendarReminderCard?> = _card.asStateFlow()

    private val _corner = MutableStateFlow<CalendarCornerMessage?>(null)
    val corner: StateFlow<CalendarCornerMessage?> = _corner.asStateFlow()

    private val mutex = Mutex()
    private val batcher = CalendarReminderBatcher()
    private var primedProfile: Int? = null
    private var primed = false
    private var cornerIds = 0L

    /**
     * Home is in front again: show what is due on the card, and refresh the Calendar if Home uses it and the gate
     * allows (at most every 6 h; Home never re-asks a failed run).
     */
    suspend fun onHomeResumed() {
        val settings = data.settings.value
        val state = data.state.value
        val homeUsesCalendar = settings != null && (settings.bellOn.isNotEmpty() || settings.comingUpRow)
        if (CalendarRefreshGate.onHomeResumed(state.lastRun, state.refreshing, homeUsesCalendar, data.nowMs())) {
            data.requestRefresh(CalendarRefreshReason.HOME_RESUMED)
        }
        if (settings?.bellOn.isNullOrEmpty()) return
        mutex.withLock {
            if (_card.value != null) return
            val due = loadDue() ?: return
            val card = calendarReminderCard(due) ?: return
            batcher.nextBatch(due)
            _corner.value = null
            _card.value = card
        }
        markSeen(_card.value?.episodeKeys.orEmpty())
    }

    /** Later, Back or an action on the card: it goes and everything on it stays seen. */
    fun dismissCard() {
        _card.value = null
    }

    fun dismissCorner(id: Long) {
        if (_corner.value?.id == id) _corner.value = null
    }

    /**
     * One foreground check for the corner note. What is already due at the first check belongs to the Home card,
     * not the corner. Returns how long to wait before the next check.
     */
    suspend fun checkCorner(): Duration {
        mutex.withLock {
            val state = data.state.value
            val settings = data.settings.value
            if (!primed || primedProfile != state.profileId) {
                primed = true
                primedProfile = state.profileId
                batcher.reset()
                loadDue()?.let { batcher.nextBatch(it) }
            } else {
                val due = loadDue()
                if (due != null) {
                    val batch = batcher.nextBatch(due)
                    if (batch.isNotEmpty() && settings?.cornerMessage == true && _card.value == null) {
                        _corner.value = CalendarCornerMessage(++cornerIds, batch)
                    }
                }
            }
            val now = java.time.Instant.ofEpochMilli(data.nowMs())
            val next = calendarNextReminderAt(state.titles, settings?.bellOn.orEmpty(), state.seenReminders, now)
            return calendarReminderCheckDelay(next, now)
        }
    }

    private suspend fun loadDue(): CalendarDueReminders? = try {
        data.dueReminders().takeIf { it.count > 0 }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "reminders unavailable: ${error.message}")
        null
    }

    private suspend fun markSeen(keys: Collection<String>) {
        if (keys.isEmpty()) return
        try {
            data.markSeen(keys)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "could not mark reminders seen: ${error.message}")
        }
    }

    internal companion object {
        const val TAG = "CalendarReminders"
        const val CORNER_SHOWN_MS = 8_000L
    }
}
