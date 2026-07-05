package com.nuvio.tv.core.iptv.refresh

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.isM3UBacked
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamTmdbResolver
import com.nuvio.tv.core.player.PlaybackActivityTracker
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * Periodic IPTV catalog refresh. Scans the active profile's enabled playlists and refreshes the ones
 * whose [XtreamAccount.autoRefreshHours] window has elapsed (see [IptvRefreshDue]):
 *  - **M3U** (url/file): re-ingest via [com.nuvio.tv.core.iptv.M3UClient.ensureIngested] `force=true`
 *    (atomic catalog swap in the DB) + XMLTV EPG refresh (piggybacked inside ingest). The DB's
 *    `built_at` is the last-refresh timestamp.
 *  - **Xtream** (API-on-demand): nothing to re-ingest; warm the category lists (so a stale cache is
 *    refreshed) and bump [IptvRefreshStore]'s checked timestamp.
 *
 * Skip-while-playing: checks [PlaybackActivityTracker] at run time and returns [Result.retry] if any
 * stream is active, so a catalog swap never runs mid-playback. Everything else is best-effort — a
 * single playlist failure is logged and the run still succeeds so WorkManager keeps the schedule.
 */
@HiltWorker
class IptvRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val accountStore: XtreamAccountStore,
    private val clientFactory: IptvClientFactory,
    private val contentDb: IptvContentDb,
    private val refreshStore: IptvRefreshStore,
    private val playbackActivity: PlaybackActivityTracker,
    private val resolver: XtreamTmdbResolver,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Don't re-ingest (atomic catalog swap) while a stream is playing — try again later.
        if (playbackActivity.isPlaybackActive()) {
            Log.i(TAG, "playback active — deferring IPTV refresh")
            return Result.retry()
        }

        val accounts = runCatching { accountStore.accounts.first() }.getOrDefault(emptyList())
        val enabled = accounts.filter { it.enabled }
        val now = System.currentTimeMillis()

        val candidates = enabled.map { acc ->
            RefreshCandidate(
                playlistId = acc.id,
                enabled = true,
                autoRefreshHours = acc.autoRefreshHours,
                lastRefreshMs = lastRefreshFor(acc),
            )
        }
        val dueIds = IptvRefreshDue.duePlaylists(candidates, now).map { it.playlistId }.toSet()
        val due = enabled.filter { it.id in dueIds }

        if (due.isEmpty()) {
            Log.i(TAG, "no playlists due (enabled=${enabled.size})")
            return Result.success()
        }

        var refreshed = 0
        var failed = 0
        for (acc in due) {
            // Bail out mid-batch if playback started (e.g. a long M3U re-ingest queued behind others).
            if (playbackActivity.isPlaybackActive()) {
                Log.i(TAG, "playback started mid-refresh — retrying remaining")
                return Result.retry()
            }
            val ok = runCatching { refresh(acc) }
                .onFailure { Log.w(TAG, "refresh failed for ${acc.name}", it) }
                .isSuccess
            if (ok) refreshed++ else failed++
        }

        Log.i(TAG, "IPTV refresh done: refreshed=$refreshed failed=$failed (due=${due.size}, enabled=${enabled.size})")
        return Result.success()
    }

    /** Last-refresh timestamp: M3U → the ingested catalog's built_at; Xtream → the checked pref. */
    private suspend fun lastRefreshFor(acc: XtreamAccount): Long? =
        if (acc.isM3UBacked()) contentDb.builtAt(acc.id) else refreshStore.lastCheckedMs(acc.id)

    private suspend fun refresh(acc: XtreamAccount) {
        if (acc.isM3UBacked()) {
            // Re-ingest (fetch + parse + atomic swap) + piggybacked XMLTV refresh; updates built_at.
            clientFactory.m3u().ensureIngested(acc, force = true)
        } else {
            // Xtream is API-on-demand — just warm the category lists so a stale cache refreshes, then
            // record the check so the window resets. The three lists are independent — fetch parallel.
            val client = clientFactory.clientFor(acc)
            coroutineScope {
                listOf(
                    async { client.liveCategories(acc) },
                    async { client.vodCategories(acc) },
                    async { client.seriesCategories(acc) },
                ).awaitAll()
            }
            // Sync the match index on the playlist's own cadence: an incremental diff, so
            // unchanged titles cost a fingerprint check and only new/renamed ones re-index.
            // ensureIndexed never throws (failures back off internally).
            if (acc.isXtream()) {
                resolver.ensureIndexed(acc, MatchKind.MOVIE, force = true)
                resolver.ensureIndexed(acc, MatchKind.SERIES, force = true)
            }
            refreshStore.markChecked(acc.id)
        }
    }

    companion object {
        private const val TAG = "IptvRefreshWorker"
    }
}
