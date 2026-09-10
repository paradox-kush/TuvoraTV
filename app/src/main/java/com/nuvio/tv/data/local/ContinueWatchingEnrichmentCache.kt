package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Type
import javax.inject.Inject
import javax.inject.Singleton

data class CachedNextUpItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val episodeDescription: String? = null,
    val thumbnail: String?,
    val released: String? = null,
    val hasAired: Boolean = true,
    val airDateLabel: String? = null,
    val lastWatched: Long,
    val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val sortTimestamp: Long,
    val releaseTimestamp: Long? = null,
    val isReleaseAlert: Boolean = false,
    val isNewSeasonRelease: Boolean = false,
    val seedSeason: Int? = null,
    val seedEpisode: Int? = null,
    val contentLanguage: String? = null
)

data class CachedInProgressItem(
    val contentId: String,
    val contentType: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long,
    val progressPercent: Float?,
    val episodeThumbnail: String? = null,
    val episodeDescription: String? = null,
    val episodeImdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    val releaseInfo: String? = null,
    val contentLanguage: String? = null
)

@Singleton
class ContinueWatchingEnrichmentCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager
) {
    /**
     * Read a disposable snapshot file with two independent bounds enforced *before/while* consuming,
     * never by materializing the whole thing first (both readers are on startup / background-worker
     * paths, so a corrupt or runaway file must not spike memory on a low-RAM TV):
     *  - BYTE cap ([MAX_CACHE_BYTES]) checked on `file.length()` before any read — an over-cap file is
     *    dropped (it is a rebuildable derived cache; a real one is a few KB).
     *  - decoded-RECORD cap ([MAX_RECORDS]) enforced while streaming the JSON array with [JsonReader],
     *    so we stop after the cap instead of decoding an unbounded number of objects into a list.
     * A record-capped file is kept (it is valid, just clamped); only an over-byte or unparseable file
     * is deleted — and only under [mutex], so a concurrent writer's newer valid snapshot is never
     * dropped mid-write.
     */
    private fun <T> readBoundedSnapshot(file: File, elementType: Type): List<T> {
        if (!file.exists()) return emptyList()
        val size = file.length()
        if (size > MAX_CACHE_BYTES) {
            Log.w(TAG, "cw snapshot ${file.name} is ${size}B > ${MAX_CACHE_BYTES}B cap; dropping (rebuilds)")
            runCatching { file.delete() }
            return emptyList()
        }
        return try {
            file.bufferedReader().use { reader ->
                gson.newJsonReader(reader).use { json ->
                    val out = ArrayList<T>()
                    json.beginArray()
                    while (json.hasNext()) {
                        if (out.size >= MAX_RECORDS) {
                            Log.w(TAG, "cw snapshot ${file.name} exceeded $MAX_RECORDS records; using the first $MAX_RECORDS")
                            break
                        }
                        out.add(gson.fromJson(json, elementType))
                    }
                    out
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cw snapshot ${file.name} unparseable: ${e.message}; dropping")
            runCatching { file.delete() }
            emptyList()
        }
    }

    companion object {
        private const val TAG = "CwEnrichCache"
        private const val THROTTLE_MS = 1_000L
        // Disposable snapshot cache; a real one is a few KB. Past this it is corrupt/runaway → drop.
        private const val MAX_CACHE_BYTES = 4L * 1024 * 1024
        // Continue-watching is a bounded UI list; cap what we decode AND what we write. A real list is
        // tens of items — 500 is generous headroom and bounds a runaway producer or a hostile file.
        private const val MAX_RECORDS = 500
    }

    private val gson = Gson()
    private val mutex = Mutex()
    @Volatile private var lastNextUpWriteMs = 0L
    @Volatile private var lastInProgressWriteMs = 0L
    @Volatile private var lastNextUpHash = 0
    @Volatile private var lastInProgressHash = 0

    /** Incremented when cache is cleared; observers can collect to trigger refresh. */
    private val _cacheCleared = kotlinx.coroutines.flow.MutableStateFlow(0)
    val cacheCleared: kotlinx.coroutines.flow.StateFlow<Int> = _cacheCleared

    /** Incremented on every successful snapshot write; channel sync observes this. */
    private val _snapshotVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val snapshotVersion: kotlinx.coroutines.flow.StateFlow<Int> = _snapshotVersion

    // --- Next Up snapshot cache ---

    private fun nextUpFile(): File {
        val profileId = profileManager.activeProfileId.value
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "nextup_${profileId}.json")
    }

    suspend fun getNextUpSnapshot(): List<CachedNextUpItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                readBoundedSnapshot<CachedNextUpItem>(nextUpFile(), CachedNextUpItem::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read next-up cache: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveNextUpSnapshot(items: List<CachedNextUpItem>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val capped = if (items.size > MAX_RECORDS) items.take(MAX_RECORDS) else items // bound the write (producer cap)
        val contentHash = capped.hashCode()
        if (!force) {
            if (contentHash == lastNextUpHash) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastNextUpWriteMs < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            try {
                val file = nextUpFile()
                atomicWrite(file, gson.toJson(capped))
                lastNextUpWriteMs = System.currentTimeMillis()
                lastNextUpHash = contentHash
                _snapshotVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write next-up cache: ${e.message}")
            }
        }
    }

    // --- In-progress snapshot cache ---

    private fun inProgressFile(): File {
        val profileId = profileManager.activeProfileId.value
        val dir = File(context.filesDir, "cw_enrichment")
        dir.mkdirs()
        return File(dir, "inprogress_${profileId}.json")
    }

    suspend fun getInProgressSnapshot(): List<CachedInProgressItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                readBoundedSnapshot<CachedInProgressItem>(inProgressFile(), CachedInProgressItem::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read in-progress cache: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * @param force bypass throttle and content-change check (use at end of pipeline or for clears)
     */
    suspend fun saveInProgressSnapshot(items: List<CachedInProgressItem>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val capped = if (items.size > MAX_RECORDS) items.take(MAX_RECORDS) else items // bound the write (producer cap)
        val contentHash = capped.hashCode()
        if (!force) {
            if (contentHash == lastInProgressHash) return@withContext
            val now = System.currentTimeMillis()
            if (now - lastInProgressWriteMs < THROTTLE_MS) return@withContext
        }
        mutex.withLock {
            try {
                val file = inProgressFile()
                atomicWrite(file, gson.toJson(capped))
                lastInProgressWriteMs = System.currentTimeMillis()
                lastInProgressHash = contentHash
                _snapshotVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write in-progress cache: ${e.message}")
            }
        }
    }

    /**
     * Deletes all CW enrichment cache files for the active profile.
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                nextUpFile().delete()
                inProgressFile().delete()
                lastNextUpHash = 0
                lastInProgressHash = 0
                Log.d(TAG, "Cleared CW enrichment cache for profile ${profileManager.activeProfileId.value}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear CW enrichment cache: ${e.message}")
            }
        }
        _cacheCleared.value++
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // renameTo can fail on some filesystems; fall back to copy+delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

}
