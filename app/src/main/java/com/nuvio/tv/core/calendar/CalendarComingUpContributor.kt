// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.contracts.ContributedHomeRow
import com.nuvio.tv.core.contracts.ContributedPage
import com.nuvio.tv.core.contracts.ContributedRowDeclaration
import com.nuvio.tv.core.contracts.HomeSectionContributor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * The optional Home "Coming up" row (off by default; the switch is in the Calendar's settings). A plain contributed
 * row, so it renders in every Home layout and joins the row order / hide settings. It is built from the stored
 * Calendar only: [sections] never makes a request, whatever `forceRefresh` says.
 */
@Singleton
class CalendarComingUpContributor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val refresher: CalendarRefresher,
    private val settingsStore: CalendarSettingsDataStore,
    @CalendarClock private val clock: Clock
) : HomeSectionContributor {
    override val name: String = "calendar"

    override fun declaredRows(): List<ContributedRowDeclaration> =
        if (settingsStore.settings.value?.comingUpRow == true) {
            listOf(ContributedRowDeclaration(ROW_KEY, context.getString(R.string.calendar_coming_up_row), ""))
        } else {
            emptyList()
        }

    override suspend fun sections(forceRefresh: Boolean): List<ContributedHomeRow> {
        val settings = settingsStore.settings.value ?: return emptyList()
        if (!settings.comingUpRow) return emptyList()
        refresher.start()
        val state = refresher.state.value
        if (!state.loaded) return emptyList()
        val zone = settings.resolvedZone()
        val items = buildCalendarComingUp(state.titles, state.watched, clock.instant(), zone).mapNotNull { it.toPreview() }
        if (items.isEmpty()) return emptyList()
        return listOf(
            ContributedHomeRow(
                key = ROW_KEY,
                sourceKey = SOURCE_KEY,
                listId = "coming_up",
                title = context.getString(R.string.calendar_coming_up_row),
                subtitle = "",
                rawType = "series",
                items = items,
                hasMore = false
            )
        )
    }

    override fun ownsSource(sourceKey: String): Boolean = sourceKey == SOURCE_KEY

    override suspend fun loadSourcePage(sourceKey: String, listId: String, skip: Int?): ContributedPage =
        ContributedPage(emptyList(), null)

    /** The row switch, or what it would show, changed. Never a timer. */
    override val changes: Flow<Unit>
        get() = combine(settingsStore.settings, refresher.state) { settings, state ->
            if (settings?.comingUpRow == true) state.titles.hashCode() to true else 0 to false
        }.distinctUntilChanged().drop(1).map { }

    private fun CalendarComingUpItem.toPreview(): MetaPreview? {
        val id = calendarContentIds(title).firstOrNull() ?: return null
        val type = if (title.type == CalendarTitleType.MOVIE) ContentType.MOVIE else ContentType.SERIES
        return MetaPreview(
            id = id,
            type = type,
            name = title.name ?: return null,
            poster = title.poster,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = describe(),
            releaseInfo = whenLabel(),
            imdbRating = null,
            genres = emptyList()
        )
    }

    private fun CalendarComingUpItem.describe(): String {
        val episode = if (entry.kind == CalendarEntryKind.EPISODE && entry.season != null && entry.episode != null) {
            context.getString(R.string.calendar_episode_code, entry.season, entry.episode)
        } else null
        return listOfNotNull(episode, entry.shownTitle, whenLabel()).joinToString(" · ")
    }

    private fun CalendarComingUpItem.whenLabel(): String {
        val locale = Locale.getDefault()
        val timeText = time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        val day = when (whenKind) {
            CalendarComingUpWhen.NEW -> context.getString(R.string.calendar_new_episode)
            CalendarComingUpWhen.TODAY -> context.getString(R.string.calendar_today)
            CalendarComingUpWhen.TONIGHT -> context.getString(R.string.calendar_tonight)
            CalendarComingUpWhen.TOMORROW -> context.getString(R.string.calendar_tomorrow)
            CalendarComingUpWhen.WEEKDAY -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            CalendarComingUpWhen.DATE -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
        return if (timeText != null && whenKind != CalendarComingUpWhen.NEW) "$day $timeText" else day
    }

    companion object {
        const val ROW_KEY = "calendar:coming_up"
        const val SOURCE_KEY = "calendar"
    }
}
