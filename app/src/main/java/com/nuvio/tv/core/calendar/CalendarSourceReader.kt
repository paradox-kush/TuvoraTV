// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import android.util.Log
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.repository.LibraryRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the app already holds that says which titles the viewer follows, and which episodes they watched.
 * [unreadOrigins] are the sources that could not be read this time; titles the Calendar holds from them are
 * carried over instead of disappearing.
 */
data class CalendarFollowedSnapshot(
    val candidates: List<CalendarTitleCandidate> = emptyList(),
    val watched: CalendarWatchedIndex = CalendarWatchedIndex(),
    val unreadOrigins: Set<CalendarOrigin> = emptySet()
)

/**
 * Reads only what the app already holds (no network, never starts a sync): the library (its own source mode,
 * so a Trakt-backed library counts), watch progress, watched items and Continue Watching's Next Up. Simkl, MDBList
 * and other trackers reach the Calendar through those when they are the active source.
 */
@Singleton
class CalendarSourceReader @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val enrichmentCache: ContinueWatchingEnrichmentCache
) {
    suspend fun read(profileId: Int): CalendarFollowedSnapshot {
        val candidates = mutableListOf<CalendarTitleCandidate>()
        val watched = CalendarWatchedIndex.Builder()
        val unread = mutableSetOf<CalendarOrigin>()

        part("library", CalendarOrigin.LIBRARY, unread) {
            libraryRepository.libraryItems.first().forEach { item ->
                val type = calendarTitleType(item.type) ?: return@forEach
                val ids = CalendarIds.fromContentId(item.id).merged(
                    CalendarIds(imdb = item.imdbId?.takeIf { it.startsWith("tt") }, tmdb = item.tmdbId?.toLong(), trakt = item.traktId?.toLong())
                )
                candidates += CalendarTitleCandidate(ids, type, CalendarOrigin.LIBRARY, item.listedAt.toInstantOrNull(), item.name, item.id, item.poster)
            }
        }
        part("progress", CalendarOrigin.PROGRESS, unread) {
            watchProgressPreferences.observeAllProgress(profileId).first()
                .filter { calendarTitleType(it.contentType) == CalendarTitleType.SERIES }
                .forEach { progress ->
                    candidates += CalendarTitleCandidate(
                        CalendarIds.fromContentId(progress.contentId), CalendarTitleType.SERIES, CalendarOrigin.PROGRESS,
                        progress.lastWatched.toInstantOrNull(), progress.name, progress.contentId, progress.poster
                    )
                    if (progress.isCompleted()) watched.add(progress.contentId, progress.season, progress.episode)
                }
            enrichmentCache.getNextUpSnapshot().forEach { item ->
                candidates += CalendarTitleCandidate(
                    CalendarIds.fromContentId(item.contentId), CalendarTitleType.SERIES, CalendarOrigin.PROGRESS,
                    item.lastWatched.toInstantOrNull(), item.name, item.contentId, item.poster
                )
            }
        }
        part("watched", CalendarOrigin.WATCHED, unread) {
            watchedItemsPreferences.observeAllItems(profileId).first()
                .filter { it.season != null || calendarTitleType(it.contentType) == CalendarTitleType.SERIES }
                .groupBy { it.contentId }
                .forEach { (contentId, items) ->
                    items.forEach { watched.add(contentId, it.season, it.episode) }
                    candidates += CalendarTitleCandidate(
                        CalendarIds.fromContentId(contentId), CalendarTitleType.SERIES, CalendarOrigin.WATCHED,
                        items.maxOf { it.watchedAt }.toInstantOrNull(), items.first().title, contentId, items.first().poster
                    )
                }
        }
        return CalendarFollowedSnapshot(candidates, watched.build(), unread)
    }

    private suspend fun part(name: String, origin: CalendarOrigin, unread: MutableSet<CalendarOrigin>, block: suspend () -> Unit) {
        val finished = try {
            withTimeoutOrNull(PART_TIMEOUT_MS) { block(); true } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Skipped $name: ${error.message}")
            false
        }
        if (!finished) unread += origin
    }

    private companion object {
        const val TAG = "CalendarSources"
        const val PART_TIMEOUT_MS = 5_000L
    }
}

private fun Long.toInstantOrNull(): Instant? = takeIf { it > 0L }?.let(Instant::ofEpochMilli)

/**
 * Titles the Calendar already holds from sources that could not be read this time, as candidates again, so a slow
 * source does not take them off the Calendar. The follow rules still apply to them.
 */
fun carriedCalendarCandidates(
    stored: List<CalendarTitleRecord>,
    unreadOrigins: Set<CalendarOrigin>
): List<CalendarTitleCandidate> {
    if (unreadOrigins.isEmpty()) return emptyList()
    return stored.flatMap { record ->
        record.origins.filter { it in unreadOrigins }.map { origin ->
            CalendarTitleCandidate(
                record.ids, record.type, origin, record.lastActivityMs?.let(Instant::ofEpochMilli),
                record.name, record.contentId, record.poster
            )
        }
    }
}
