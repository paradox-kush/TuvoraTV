package com.nuvio.tv.core.iptv.refresh

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first

/**
 * Periodic IPTV catalog refresh. Scans the active profile's enabled playlists and refreshes the ones
 * whose [XtreamAccount.autoRefreshHours] window has elapsed (see [IptvRefreshDue]):
 *  - **M3U** (url/file): re-ingest via [com.nuvio.tv.core.iptv.M3UClient.ensureIngested] `force=true`
 *    (atomic catalog swap in the DB) + XMLTV EPG refresh (piggybacked inside ingest). The DB's
 *    `built_at` is the last-refresh timestamp.
 *  - **Xtream**: refresh only stale enabled catalogue kinds, including Live. Their committed
 *    index timestamps determine freshness; a reachability check never substitutes for fresh categories.
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
    private val matchIndex: XtreamMatchIndex,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Don't re-ingest (atomic catalog swap) while a stream is playing — try again later.
        if (playbackActivity.isPlaybackActive()) {
            Log.i(TAG, "playback active — deferring IPTV refresh")
            return Result.retry()
        }
        // Recovery safe mode: the app is crash-looping before it reaches an interactive screen —
        // this worker does the same force=true M3U re-ingest + index rebuild as the warm-up, so a
        // UI-level gate would be bypassed here. Skip this run; the next healthy launch clears it.
        if (com.nuvio.tv.core.journal.StartupJournal.isSafeMode) {
            Log.i(TAG, "recovery safe mode — skipping IPTV refresh this run")
            return Result.success()
        }

        val accounts = runCatching { accountStore.accounts.first() }.getOrDefault(emptyList())
        val enabled = accounts.filter { it.availableOnDevice }
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
        val due = enabled.filter { it.id in dueIds && (it.isXtream() || it.isM3UBacked()) }

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
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(TAG, "refresh failed for ${acc.name}", it) }
                .isSuccess
            if (ok) {
                refreshed++
            } else failed++
        }

        Log.i(TAG, "IPTV refresh done: refreshed=$refreshed failed=$failed (due=${due.size}, enabled=${enabled.size})")
        return Result.success()
    }

    /** Last-refresh timestamp: M3U → the ingested catalog's built_at; Xtream → the oldest enabled index. */
    private suspend fun lastRefreshFor(acc: XtreamAccount): Long? =
        if (acc.isM3UBacked()) contentDb.builtAt(acc.id) else if (acc.isXtream()) {
            MatchKind.entries.filter { kind -> acc.typeEnabled(when (kind) {
                MatchKind.LIVE -> XtreamAccount.TYPE_LIVE
                MatchKind.MOVIE -> XtreamAccount.TYPE_MOVIES
                MatchKind.SERIES -> XtreamAccount.TYPE_SERIES
            }) }.map { matchIndex.builtAt(acc.id, it) ?: 0L }.minOrNull()
        } else refreshStore.lastCheckedMs(acc.id)

    private suspend fun refresh(acc: XtreamAccount) {
        if (acc.isM3UBacked()) {
            // Re-ingest (fetch + parse + atomic swap) + piggybacked XMLTV refresh; updates built_at.
            clientFactory.m3u().ensureIngested(acc, force = true)
        } else {
            val kinds = MatchKind.entries.filter { kind ->
                acc.typeEnabled(when (kind) {
                    MatchKind.LIVE -> XtreamAccount.TYPE_LIVE
                    MatchKind.MOVIE -> XtreamAccount.TYPE_MOVIES
                    MatchKind.SERIES -> XtreamAccount.TYPE_SERIES
                })
            }
            check(kinds.isNotEmpty()) { "No enabled catalogue kinds" }
            val stamps = kinds.associateWith { matchIndex.builtAt(acc.id, it) }
            for (kind in com.nuvio.tv.core.iptv.IptvCatalogFreshness.dueKinds(stamps, acc.autoRefreshHours, System.currentTimeMillis())) resolver.ensureIndexed(acc, kind, awaitRefresh = true)
            check(kinds.all { !com.nuvio.tv.core.iptv.IptvCatalogFreshness.stale(matchIndex.builtAt(acc.id, it), acc.autoRefreshHours, System.currentTimeMillis()) }) {
                "Catalogue refresh did not commit all enabled kinds"
            }
            refreshStore.markChecked(acc.id)
        }
    }

    companion object {
        private const val TAG = "IptvRefreshWorker"
    }
}
