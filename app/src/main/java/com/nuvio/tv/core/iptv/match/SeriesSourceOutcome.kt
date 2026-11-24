package com.nuvio.tv.core.iptv.match

import com.nuvio.tv.core.iptv.HttpStatusException
import com.nuvio.tv.core.iptv.IptvLoadTimeoutException
import com.nuvio.tv.core.iptv.LoadOutcome
import com.nuvio.tv.core.iptv.XtreamEpisode
import com.nuvio.tv.core.iptv.XtreamParseException
import com.nuvio.tv.core.iptv.XtreamSeriesDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InterruptedIOException

/**
 * B134: what one playlist can say about a TMDB (show, season, episode), with a failed lookup kept distinct from
 * "not on this playlist". A failed `get_series_info` used to be `getOrNull() ?: emptyList()`, so a 403 or a
 * garbled body read as "No streams found" (repo-root CLAUDE.md: a failure is never "empty"). Twin of NuvioMobile's
 * `SeriesSourceOutcome` (hand-ported: TV's client returns a non-null detail and uses OkHttp, not Ktor).
 */
sealed interface SeriesSourceOutcome {
    /** At least one edition holds the episode. */
    data class Found(val picks: List<EditionEpisode>) : SeriesSourceOutcome

    /** No catalog entry for the show. Genuinely not on this playlist. */
    data object NoMatch : SeriesSourceOutcome

    /** `get_series_info` failed, so we do not know. [kind] is a neutral category, never a host or message. */
    data class InfoFailed(val kind: SeriesInfoFailureKind) : SeriesSourceOutcome

    /** The show is there and its info loaded, but no edition has this season and episode. */
    data object NoEpisodeForSE : SeriesSourceOutcome
}

data class EditionEpisode(val edition: IndexedItem, val episode: XtreamEpisode)

/** Neutral failure categories for UI copy and telemetry. [key] values are a closed set. */
enum class SeriesInfoFailureKind(val key: String) {
    HTTP_4XX("http_4xx"),
    HTTP_5XX("http_5xx"),
    HTTP_OTHER("http_other"),
    TIMEOUT("timeout"),
    PARSE("parse"),
    OTHER("other"),
}

/** Thrown for a failed series lookup; its message is shown to the viewer and carries the kind only. */
class XtreamSeriesInfoException(val kind: SeriesInfoFailureKind, cause: Throwable? = null) :
    IllegalStateException("Couldn't load this series from the playlist (${kind.key}). Try again.", cause)

object SeriesSourcePolicy {

    fun classify(error: Throwable): SeriesInfoFailureKind = when (error) {
        is HttpStatusException -> when (error.status) {
            in 400..499 -> SeriesInfoFailureKind.HTTP_4XX
            in 500..599 -> SeriesInfoFailureKind.HTTP_5XX
            else -> SeriesInfoFailureKind.HTTP_OTHER
        }
        is XtreamParseException -> SeriesInfoFailureKind.PARSE
        // SocketTimeoutException and OkHttp's call timeout are both InterruptedIOException.
        is IptvLoadTimeoutException, is TimeoutCancellationException, is InterruptedIOException -> SeriesInfoFailureKind.TIMEOUT
        else -> SeriesInfoFailureKind.OTHER
    }

    /** One edition's `get_series_info` result mapped to an outcome. */
    fun fromInfo(
        edition: IndexedItem,
        info: Result<XtreamSeriesDetail>,
        season: Int,
        episode: Int,
    ): SeriesSourceOutcome = info.fold(
        onSuccess = { detail ->
            val picks = XtreamSeriesEpisodePolicy.pickEpisodes(edition, detail.episodes, season, episode)
                .map { EditionEpisode(edition, it) }
            if (picks.isEmpty()) SeriesSourceOutcome.NoEpisodeForSE else SeriesSourceOutcome.Found(picks)
        },
        onFailure = { SeriesSourceOutcome.InfoFailed(classify(it)) },
    )

    /**
     * Folds the per-edition outcomes. Any hit wins (a failed sibling edition must not hide it). Otherwise a failure
     * beats "no episode": the failed edition may have been the one holding it, so we cannot claim it is absent.
     */
    fun combine(outcomes: List<SeriesSourceOutcome>): SeriesSourceOutcome {
        val picks = outcomes.filterIsInstance<SeriesSourceOutcome.Found>().flatMap { it.picks }
        if (picks.isNotEmpty()) return SeriesSourceOutcome.Found(picks)
        outcomes.filterIsInstance<SeriesSourceOutcome.InfoFailed>().firstOrNull()?.let { return it }
        if (outcomes.any { it is SeriesSourceOutcome.NoEpisodeForSE }) return SeriesSourceOutcome.NoEpisodeForSE
        return SeriesSourceOutcome.NoMatch
    }

    /** Runs [fetch] once per edition (in order) and folds the outcomes. No editions is [SeriesSourceOutcome.NoMatch]. */
    suspend fun resolve(
        editions: List<IndexedItem>,
        season: Int,
        episode: Int,
        fetch: suspend (IndexedItem) -> Result<XtreamSeriesDetail>,
    ): SeriesSourceOutcome = combine(
        editions.map { ed ->
            val result = fetch(ed)
            result.exceptionOrNull()?.let { error ->
                // runCatching also captures cancellation: the caller leaving must propagate, an inner timeout is a failure.
                if (error is CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (error !is TimeoutCancellationException) throw error
                }
            }
            fromInfo(ed, result, season, episode)
        },
    )
}

/** A bounded load's outcome as the Result the client returns. A failure keeps its own error. */
fun <T> LoadOutcome<T>.toResult(): Result<T> = when (this) {
    is LoadOutcome.Loaded -> Result.success(value)
    is LoadOutcome.Empty -> Result.success(value)
    is LoadOutcome.Failed -> Result.failure(error ?: IllegalStateException("load failed"))
}

