package com.nuvio.tv.core.rec

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.domain.model.ContentType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Recommendation logging, reachable from composition without threading it through call sites.
 *
 * The home rows already take upwards of fifty parameters; adding two more to every one of them
 * to carry telemetry would be the wrong trade. A CompositionLocal would also work, but it needs
 * a provider wrapped around the tree — and MainActivity's `setContent` is a 450-line expression
 * where adding a brace level is a merge hazard for no benefit. Resolving through a Hilt entry
 * point needs no wiring at any call site at all.
 *
 * Resolution failure returns null and every helper here no-ops, which is the fail-open behaviour
 * the rest of the package promises. Previews and tests get that for free.
 */
@Immutable
data class RecTelemetry(
    val logger: RecEventLogger,
    val dedupe: RecImpressionDedupe,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecTelemetryEntryPoint {
    fun recEventLogger(): RecEventLogger
    fun recImpressionDedupe(): RecImpressionDedupe
}

/** Null when the graph cannot be reached (previews, tests) — callers must treat that as "off". */
@Composable
fun rememberRecTelemetry(): RecTelemetry? {
    val context = LocalContext.current
    return remember(context) { resolveRecTelemetry(context) }
}

private fun resolveRecTelemetry(context: Context): RecTelemetry? = runCatching {
    val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        RecTelemetryEntryPoint::class.java,
    )
    RecTelemetry(entryPoint.recEventLogger(), entryPoint.recImpressionDedupe())
}.getOrNull()

/** Maps the app's content taxonomy onto the event stream's. */
fun ContentType.toRecContentType(): String = when (this) {
    ContentType.MOVIE -> RecContentType.MOVIE
    ContentType.SERIES -> RecContentType.SERIES
    ContentType.CHANNEL, ContentType.TV -> RecContentType.LIVE
    ContentType.UNKNOWN -> RecContentType.MOVIE
}

/**
 * Log impressions for one shelf. Safe to call unconditionally — does nothing when telemetry is
 * not installed or the user has opted out.
 */
@Composable
fun RecRowImpressions(
    listState: LazyListState,
    surface: String,
    rowId: String,
    rowIndex: Int,
    itemAt: (index: Int) -> RecImpressionItem?,
) {
    val telemetry = rememberRecTelemetry() ?: return
    RecImpressionEffect(
        listState = listState,
        surface = surface,
        rowId = rowId,
        rowIndex = rowIndex,
        logger = telemetry.logger,
        dedupe = telemetry.dedupe,
        itemAt = itemAt,
    )
}

/**
 * Log a click, carrying the same row context the impression carried so the two are joinable.
 * A click whose impression was never logged is still worth having — it just cannot contribute
 * to a click-through rate.
 */
fun RecTelemetry?.logClick(
    surface: String,
    rowId: String?,
    rowIndex: Int?,
    itemPosition: Int?,
    item: RecImpressionItem,
) {
    this ?: return
    logger.log(
        RecEvent(
            eventType = RecEventType.CLICK,
            surface = surface,
            contentType = item.contentType,
            rowId = rowId,
            rowIndex = rowIndex,
            itemPosition = itemPosition,
            itemId = item.itemId,
            tmdbId = item.tmdbId,
            season = item.season,
            episode = item.episode,
        )
    )
}
