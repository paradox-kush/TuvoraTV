package com.nuvio.tv.core.rec

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** What a shelf slot needs to describe itself to the recommendation stream. */
data class RecImpressionItem(
    val itemId: String,
    val contentType: String,
    val season: Int? = null,
    val episode: Int? = null,
) {
    /** Free when the id is already a TMDB one; never worth a lookup on the impression path. */
    val tmdbId: Int?
        get() = itemId.removePrefix("tmdb:").takeIf { it != itemId }?.toIntOrNull()
}

/** Item must be at least this visible to count as seen. */
private const val MIN_VISIBLE_FRACTION = 0.5f

/** ...and must stay that way this long, so a fast scroll past a poster is not an impression. */
private const val DWELL_MS = 500L

/** Bound on the per-session dedupe set; far above a realistic session, and stops a runaway row. */
private const val MAX_TRACKED_KEYS = 5_000

/**
 * Remembers which (session, row, item) triples have already been logged.
 *
 * Dedupe has to outlive composition: a shelf that scrolls off screen and back, or a row that
 * recomposes on a catalogue refresh, must not re-log the same posters. Keying on the session and
 * clearing when it rolls over is what keeps "impressions per session" a meaningful number rather
 * than a count of how much the user scrolled back and forth.
 */
@Singleton
class RecImpressionDedupe @Inject constructor() {
    private val seen = HashSet<String>()
    private var sessionId: String? = null

    @Synchronized
    fun firstTimeThisSession(sessionId: String, rowId: String, itemId: String): Boolean {
        if (this.sessionId != sessionId) {
            this.sessionId = sessionId
            seen.clear()
        }
        if (seen.size >= MAX_TRACKED_KEYS) return false
        return seen.add("$rowId#$itemId")
    }
}

/**
 * Logs an `impression` for every item in [listState]'s row that stays at least half visible for
 * [DWELL_MS].
 *
 * Piggybacks `layoutInfo.visibleItemsInfo`, which the home rows already read for image
 * prefetching — no new measurement pass and no extra recomposition. The debounce does double
 * duty: it enforces the dwell requirement AND collapses a continuous scroll into a single
 * evaluation once the row settles, which is exactly the semantics we want (posters that flew
 * past were not really seen).
 *
 * Fails open like the rest of the package: any failure here logs nothing and disturbs nothing.
 */
@OptIn(FlowPreview::class)
@Composable
fun RecImpressionEffect(
    listState: LazyListState,
    surface: String,
    rowId: String,
    rowIndex: Int,
    logger: RecEventLogger,
    dedupe: RecImpressionDedupe,
    itemAt: (index: Int) -> RecImpressionItem?,
) {
    val currentItemAt by rememberUpdatedState(itemAt)

    LaunchedEffect(listState, rowId, rowIndex, surface) {
        snapshotFlow {
            val info = listState.layoutInfo
            val start = info.viewportStartOffset
            val end = info.viewportEndOffset
            info.visibleItemsInfo
                .filter { item ->
                    if (item.size <= 0) return@filter false
                    val visible = minOf(item.offset + item.size, end) - maxOf(item.offset, start)
                    visible.toFloat() / item.size >= MIN_VISIBLE_FRACTION
                }
                .map { it.index }
        }
            .distinctUntilChanged()
            .debounce(DWELL_MS)
            .collect { indices ->
                val sessionId = runCatching { logger.currentSessionId() }.getOrNull() ?: return@collect
                for (index in indices) {
                    val item = runCatching { currentItemAt(index) }.getOrNull() ?: continue
                    if (!dedupe.firstTimeThisSession(sessionId, rowId, item.itemId)) continue
                    logger.log(
                        RecEvent(
                            eventType = RecEventType.IMPRESSION,
                            surface = surface,
                            contentType = item.contentType,
                            rowId = rowId,
                            rowIndex = rowIndex,
                            itemPosition = index,
                            itemId = item.itemId,
                            tmdbId = item.tmdbId,
                            season = item.season,
                            episode = item.episode,
                        )
                    )
                }
            }
    }
}
