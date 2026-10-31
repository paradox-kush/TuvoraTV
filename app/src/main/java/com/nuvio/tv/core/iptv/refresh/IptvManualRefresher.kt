package com.nuvio.tv.core.iptv.refresh

import android.util.Log
import com.nuvio.tv.core.iptv.IptvClientFactory
import com.nuvio.tv.core.iptv.IptvManualRefreshPolicy
import com.nuvio.tv.core.iptv.ManualRefreshResult
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamSearchIndex
import com.nuvio.tv.core.iptv.content.IptvContentDb
import com.nuvio.tv.core.iptv.content.M3UFileStore
import com.nuvio.tv.core.iptv.isM3UBacked
import com.nuvio.tv.core.iptv.match.MatchKind
import com.nuvio.tv.core.iptv.match.XtreamMatchIndex
import com.nuvio.tv.core.iptv.match.XtreamTmdbResolver
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * UX179 "Refresh now" on TV: rebuild one playlist's catalog immediately, whatever its interval says. Honest outcomes:
 * [ManualRefreshResult.REFRESHED] only when every enabled catalogue kind's build time CHANGED during this run; a
 * deferred, failed or backed-off build is FAILED/PARTIAL, never "done". Stalker is never refreshed in the background.
 * The per-playlist cooldown lives here (monotonic clock), not per screen.
 */
@Singleton
class IptvManualRefresher @Inject constructor(
    private val clientFactory: IptvClientFactory,
    private val contentDb: IptvContentDb,
    private val resolver: XtreamTmdbResolver,
    private val matchIndex: XtreamMatchIndex,
    private val refreshStore: IptvRefreshStore,
    private val searchIndex: XtreamSearchIndex,
    private val fileStore: M3UFileStore,
) {
    private val startLock = Mutex()
    private val lastStartMark = mutableMapOf<String, TimeSource.Monotonic.ValueTimeMark>()
    /** One manual refresh at a time across playlists, like the scheduled pass (one big catalog build at once). */
    private val runLock = Mutex()

    suspend fun refreshNow(acc: XtreamAccount): ManualRefreshResult {
        val mode = IptvManualRefreshPolicy.modeFor(
            isStalker = acc.sourceType == XtreamAccount.SOURCE_STALKER,
            isFile = acc.sourceType == XtreamAccount.SOURCE_FILE,
            hasLocalCopy = acc.sourceType != XtreamAccount.SOURCE_FILE || fileStore.exists(acc.id),
        )
        if (mode == IptvManualRefreshPolicy.Mode.RELOAD_ON_OPEN) return ManualRefreshResult.RELOAD_ON_OPEN
        if (mode == IptvManualRefreshPolicy.Mode.NEEDS_LOCAL_FILE) return ManualRefreshResult.NEEDS_LOCAL_FILE
        startLock.withLock {
            val since = lastStartMark[acc.id]?.elapsedNow()?.inWholeMilliseconds
            if (IptvManualRefreshPolicy.cooldownRemainingMs(since) > 0L) return ManualRefreshResult.COOLDOWN
            lastStartMark[acc.id] = TimeSource.Monotonic.markNow()
        }
        return runLock.withLock {
            try {
                if (acc.isM3UBacked()) {
                    // Re-ingest (fetch + parse + atomic swap) + the piggybacked XMLTV refresh; updates built_at. Throws on failure.
                    clientFactory.m3u().ensureIngested(acc, force = true)
                    ManualRefreshResult.REFRESHED
                } else {
                    val kinds = MatchKind.entries.filter { kind ->
                        acc.typeEnabled(when (kind) {
                            MatchKind.LIVE -> XtreamAccount.TYPE_LIVE
                            MatchKind.MOVIE -> XtreamAccount.TYPE_MOVIES
                            MatchKind.SERIES -> XtreamAccount.TYPE_SERIES
                        })
                    }
                    val before = kinds.map { matchIndex.builtAt(acc.id, it) }
                    resolver.clearFailureBackoff(acc.id)
                    for (kind in kinds) resolver.ensureIndexed(acc, kind, force = true, awaitRefresh = true)
                    when (IptvManualRefreshPolicy.landed(before, kinds.map { matchIndex.builtAt(acc.id, it) })) {
                        IptvManualRefreshPolicy.Landed.ALL -> {
                            searchIndex.evict(acc.id)
                            refreshStore.markChecked(acc.id)
                            ManualRefreshResult.REFRESHED
                        }
                        IptvManualRefreshPolicy.Landed.SOME -> ManualRefreshResult.PARTIAL
                        IptvManualRefreshPolicy.Landed.NONE -> ManualRefreshResult.FAILED
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w("IptvManualRefresher", "manual refresh failed for ${acc.name}", t)
                ManualRefreshResult.FAILED
            }
        }
    }

    /** When [acc]'s catalog was last rebuilt (M3U: the ingest time; Xtream: the OLDEST enabled index), or null when unknown. */
    suspend fun lastRefreshAt(acc: XtreamAccount): Long? {
        val at = if (acc.isM3UBacked()) contentDb.builtAt(acc.id) else if (acc.sourceType == XtreamAccount.SOURCE_STALKER) {
            refreshStore.lastCheckedMs(acc.id)
        } else {
            MatchKind.entries.filter { kind ->
                acc.typeEnabled(when (kind) {
                    MatchKind.LIVE -> XtreamAccount.TYPE_LIVE
                    MatchKind.MOVIE -> XtreamAccount.TYPE_MOVIES
                    MatchKind.SERIES -> XtreamAccount.TYPE_SERIES
                })
            }.map { matchIndex.builtAt(acc.id, it) ?: 0L }.minOrNull()
        }
        return at?.takeIf { it > 0L }
    }
}
