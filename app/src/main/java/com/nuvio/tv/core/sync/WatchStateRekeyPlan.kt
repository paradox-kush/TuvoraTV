package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.model.WatchedMutationKey
import com.nuvio.tv.domain.model.mutationKey

/**
 * Step 0 — the synced half of moving a playlist's watch state from one id prefix to another
 * (`xtream:{oldId}:` → `xtream:{newId}:`), as a pure decision. Behavioural twin of Mobile's
 * `WatchProgressRepository.migrateIdPrefix` / `WatchedRepository.migrateIdPrefix`: every entry under
 * the old prefix is deleted remotely and its moved copy upserted under the new one, so the server
 * never keeps rows under an id that no longer exists (a later pull would resurrect them as ghost
 * Continue Watching / watched entries).
 *
 * Rules: live progress is local-only on TV (never pushed, so never deleted either); a device that
 * is not a full account syncs nothing ([EMPTY]) — the local stores still move.
 */
data class WatchStateRekeyPlan(
    val progressUpserts: Map<String, WatchProgress>,
    val progressDeletes: Set<String>,
    val watchedUpserts: List<WatchedItem>,
    val watchedDeletes: Set<WatchedMutationKey>,
) {
    val isEmpty: Boolean
        get() = progressUpserts.isEmpty() && progressDeletes.isEmpty() &&
            watchedUpserts.isEmpty() && watchedDeletes.isEmpty()

    companion object {
        val EMPTY = WatchStateRekeyPlan(emptyMap(), emptySet(), emptyList(), emptySet())

        fun build(
            progress: Map<String, WatchProgress>,
            watched: Collection<WatchedItem>,
            oldPrefix: String,
            newPrefix: String,
            fullAccount: Boolean,
        ): WatchStateRekeyPlan {
            if (!fullAccount) return EMPTY
            val moved = rekeyProgressEntries(progress, oldPrefix, newPrefix)
            val progressDeletes = moved.removedKeys.filterTo(mutableSetOf()) { key ->
                progress[key]?.let { !isLiveWatchProgress(it) } ?: true
            }
            val progressUpserts = moved.movedEntries.filterValues { !isLiveWatchProgress(it) }
            val affectedWatched = watched.filter { it.contentId.startsWith(oldPrefix) }
            return WatchStateRekeyPlan(
                progressUpserts = progressUpserts,
                progressDeletes = progressDeletes,
                watchedUpserts = affectedWatched.map { rekeyWatchedItem(it, oldPrefix, newPrefix) },
                watchedDeletes = affectedWatched.mapTo(mutableSetOf()) { it.mutationKey() },
            )
        }
    }
}

/** Result of re-keying a progress map: the full new map, the old keys that left, the entries that moved. */
data class ProgressRekey(
    val entries: Map<String, WatchProgress>,
    val removedKeys: Set<String>,
    val movedEntries: Map<String, WatchProgress>,
)

/**
 * Rewrites (or, with [newPrefix] null, drops) every progress entry whose key/contentId/videoId starts
 * with [oldPrefix]. The one rewrite both the local store and the sync plan use, so the queued upserts
 * are exactly what the store ends up holding.
 */
fun rekeyProgressEntries(
    entries: Map<String, WatchProgress>,
    oldPrefix: String,
    newPrefix: String?,
): ProgressRekey {
    val affected = { key: String, p: WatchProgress ->
        key.startsWith(oldPrefix) || p.contentId.startsWith(oldPrefix) || p.videoId.startsWith(oldPrefix)
    }
    val kept = linkedMapOf<String, WatchProgress>()
    val removed = mutableSetOf<String>()
    val moved = linkedMapOf<String, WatchProgress>()
    entries.forEach { (key, progress) ->
        if (!affected(key, progress)) {
            kept[key] = progress
            return@forEach
        }
        removed += key
        if (newPrefix != null) {
            val m = progress.copy(
                contentId = progress.contentId.rewritePrefix(oldPrefix, newPrefix),
                videoId = progress.videoId.rewritePrefix(oldPrefix, newPrefix),
            )
            moved[watchProgressKey(m)] = m
        }
    }
    return ProgressRekey(entries = kept + moved, removedKeys = removed, movedEntries = moved)
}

fun rekeyWatchedItem(item: WatchedItem, oldPrefix: String, newPrefix: String): WatchedItem =
    item.copy(contentId = item.contentId.rewritePrefix(oldPrefix, newPrefix))

/** The local/remote progress key: `{contentId}_s{S}e{E}` for an episode, else the content id. */
fun watchProgressKey(progress: WatchProgress): String =
    if (progress.season != null && progress.episode != null) {
        "${progress.contentId}_s${progress.season}e${progress.episode}"
    } else {
        progress.contentId
    }

private fun String.rewritePrefix(oldPrefix: String, newPrefix: String): String =
    if (startsWith(oldPrefix)) newPrefix + removePrefix(oldPrefix) else this
