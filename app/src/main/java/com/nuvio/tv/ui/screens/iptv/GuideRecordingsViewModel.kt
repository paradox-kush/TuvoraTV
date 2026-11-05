package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.iptv.CatchUpPlaybackCoordinator
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamProgram
import com.nuvio.tv.core.recordings.api.ListingInfo
import com.nuvio.tv.core.recordings.api.ProgrammeRecordPolicy
import com.nuvio.tv.core.recordings.api.RecordingChannel
import com.nuvio.tv.core.recordings.api.RecordingGuideKeys
import com.nuvio.tv.core.recordings.api.RecordingsAndroid
import com.nuvio.tv.core.recordings.api.RecordingsPort
import com.nuvio.tv.core.recordings.api.RecordingsState
import com.nuvio.tv.core.recordings.api.ScheduleConfirmation
import com.nuvio.tv.core.recordings.api.ScheduleConfirmationPolicy
import com.nuvio.tv.core.recordings.api.ScheduleResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the guide tells the viewer after a Record / Record series / Cancel press. */
sealed interface GuideRecordNotice {
    val title: String

    data class Scheduled(
        override val title: String,
        val confirmation: ScheduleConfirmation,
        /** Schedules to cancel on Undo (one for a single programme). */
        val scheduleIds: List<String>,
        val isSeries: Boolean,
    ) : GuideRecordNotice

    data class Cancelled(override val title: String) : GuideRecordNotice
    data class Failed(override val title: String) : GuideRecordNotice
}

/**
 * The guide's side of recordings: which programmes carry a REC badge, what the programme sheet may
 * offer, and the Record / Cancel presses. Kept apart from the (large, recomposition-sensitive) guide
 * view model so the badge set is derived ONCE here and read once by the screen, never per cell.
 */
@HiltViewModel
class GuideRecordingsViewModel @Inject constructor(
    private val port: RecordingsPort,
    private val catchUp: CatchUpPlaybackCoordinator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val capabilities get() = port.capabilities

    /** Full state, for the sheet's offer. Collect it inside the sheet, not at the guide's root. */
    val state: StateFlow<RecordingsState> = port.state

    /** channel key (the guide's contentId) -> start times of scheduled / recording programmes. Emits only when it changes. */
    val badgeKeys: StateFlow<Map<String, Set<Long>>> = port.state
        .map { RecordingGuideKeys.startsByChannel(it) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _notice = MutableStateFlow<GuideRecordNotice?>(null)
    val notice: StateFlow<GuideRecordNotice?> = _notice.asStateFlow()

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun dismissNotice() { _notice.value = null }

    /** The "allow exact times" screen, or null where the device has none (the notice then stays "may start late"). */
    fun exactAlarmSettingsIntent(): Intent? = RecordingsAndroid.exactAlarmSettingsIntent(context)

    /** Offered once, the first time an inexact schedule is made, and only when there is a screen to open. */
    private fun takeExactPromptTurn(): Boolean {
        if (exactAlarmSettingsIntent() == null) return false
        if (prefs.getBoolean(KEY_EXACT_PROMPTED, false)) return false
        prefs.edit().putBoolean(KEY_EXACT_PROMPTED, true).apply()
        return true
    }

    fun record(account: XtreamAccount, channel: GuideChannel, programme: XtreamProgram) {
        viewModelScope.launch {
            val result = guarded {
                port.scheduleProgramme(
                    channel = channelFor(account, channel),
                    title = programme.title,
                    episode = ProgrammeRecordPolicy.episodeLabel(programme.title, programme.description),
                    description = programme.description.ifBlank { null },
                    startMs = programme.startMs,
                    endMs = programme.endMs,
                    nowMs = System.currentTimeMillis(),
                )
            }
            _notice.value = if (result == null) {
                GuideRecordNotice.Failed(programme.title)
            } else {
                GuideRecordNotice.Scheduled(
                    title = programme.title,
                    confirmation = ScheduleConfirmationPolicy.forResult(result, ::takeExactPromptTurn),
                    scheduleIds = listOfNotNull((result as? ScheduleResult.Scheduled)?.schedule?.id),
                    isSeries = false,
                )
            }
        }
    }

    /** [listings] are the airings of this channel the guide already holds; later airings are added by [expandSeriesFor]. */
    fun recordSeries(account: XtreamAccount, channel: GuideChannel, programme: XtreamProgram, listings: List<XtreamProgram>) {
        viewModelScope.launch {
            val created = guarded {
                port.addSeriesRule(
                    channel = channelFor(account, channel),
                    title = programme.title,
                    keepLast = 0,
                    listings = listings.map { ListingInfo(it.title, it.description.ifBlank { null }, it.startMs, it.endMs) },
                    nowMs = System.currentTimeMillis(),
                )
            }
            _notice.value = if (created == null) {
                GuideRecordNotice.Failed(programme.title)
            } else {
                GuideRecordNotice.Scheduled(
                    title = programme.title,
                    confirmation = ScheduleConfirmationPolicy.forSeries(
                        created, port.capabilities, port.state.value.exactAllowed, ::takeExactPromptTurn,
                    ),
                    scheduleIds = created.map { it.id },
                    isSeries = true,
                )
            }
        }
    }

    /** Series rules grow as the guide shows more airings: add the new matches of each rule on this channel. Idempotent. */
    fun expandSeriesFor(channelKey: String, programmes: List<XtreamProgram>) {
        val rules = port.state.value.series.filter { it.channel.key == channelKey }
        if (rules.isEmpty() || programmes.isEmpty()) return
        val listings = programmes.map { ListingInfo(it.title, it.description.ifBlank { null }, it.startMs, it.endMs) }
        viewModelScope.launch {
            rules.forEach { rule -> guarded { port.expandSeries(rule.id, listings, System.currentTimeMillis()) } }
        }
    }

    fun cancel(scheduleId: String, title: String) {
        viewModelScope.launch {
            _notice.value = if (guarded { port.cancel(scheduleId) } != null) GuideRecordNotice.Cancelled(title) else GuideRecordNotice.Failed(title)
        }
    }

    /** Undo of the confirmation just shown. */
    fun undo(notice: GuideRecordNotice.Scheduled) {
        viewModelScope.launch {
            notice.scheduleIds.forEach { id -> guarded { port.cancel(id) } }
            _notice.value = null
        }
    }

    /** With the panel's clock offset measured (it may need one request, bounded). */
    private suspend fun channelFor(account: XtreamAccount, channel: GuideChannel): RecordingChannel {
        val offset = if (account.sourceType == XtreamAccount.SOURCE_XTREAM && channel.hasArchive && catchUp.supports(account)) {
            catchUp.recordingServerOffsetMs(account)
        } else null
        return snapshot(account, channel, offset)
    }

    /**
     * The snapshot a recording keeps of the channel (so a capture starts without the guide loaded).
     * [serverOffsetMs] is only needed to schedule; the sheet's offer does not depend on it.
     */
    fun snapshot(account: XtreamAccount, channel: GuideChannel, serverOffsetMs: Long? = null): RecordingChannel {
        val ua = account.userAgent
        return when (account.sourceType) {
            XtreamAccount.SOURCE_XTREAM -> RecordingChannelFactory.xtream(
                contentId = channel.contentId, accountId = account.id, name = channel.name, logo = channel.logo,
                liveUrl = channel.streamUrl, userAgent = ua, baseUrl = account.baseUrl,
                username = account.username, password = account.password, streamId = channel.streamId,
                hasArchive = channel.hasArchive && catchUp.supports(account), catchUpDays = channel.catchUpDays,
                serverOffsetMs = serverOffsetMs,
            )
            XtreamAccount.SOURCE_URL, XtreamAccount.SOURCE_FILE -> RecordingChannelFactory.m3u(
                contentId = channel.contentId, accountId = account.id, name = channel.name, logo = channel.logo,
                liveUrl = channel.streamUrl, userAgent = ua,
                info = channel.m3uCatchUp?.takeIf { catchUp.supports(account, it) },
                offsetMs = account.catchUpOffsetMs ?: 0L,
            )
            else -> RecordingChannelFactory.withoutCatchUp(
                contentId = channel.contentId, accountId = account.id, name = channel.name, logo = channel.logo,
                liveUrl = channel.streamUrl, userAgent = ua,
            )
        }
    }

    /** Runs [block]; null (logged, no detail shown) when it throws. Cancellation passes through. */
    private suspend fun <T : Any> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "recording action failed", e)
        null
    }

    private companion object {
        const val TAG = "GuideRecordings"
        const val PREFS = "recordings_ui"
        const val KEY_EXACT_PROMPTED = "exact_alarm_prompted"
    }
}
