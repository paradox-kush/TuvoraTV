package com.nuvio.tv.core.recommendations

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.nuvio.tv.MainActivity
import com.nuvio.tv.data.mediaserver.plex.PlexArtworkLink
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ResumeRankPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgramBuilder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun watchNextId(progress: WatchProgress): String =
        if (progress.season != null && progress.episode != null)
            "wn_${progress.contentId}_s${progress.season}e${progress.episode}"
        else
            "wn_${progress.contentId}"

    fun buildWatchNextProgram(progress: WatchProgress): WatchNextProgram =
        buildWatchNextProgram(LauncherItem(progress, ResumeRankPolicy.Kind.Resume))

    fun buildWatchNextProgram(item: LauncherItem): WatchNextProgram {
        val progress = item.progress
        val positionMs = WatchNextTypePolicy.resolvePositionMs(
            progress.position, progress.duration, progress.progressPercent
        )
        val watchNextType = WatchNextTypePolicy.typeFor(item.kind, positionMs, progress.duration)
        val isMovie = progress.contentType == "movie"
        val programType = if (isMovie) {
            TvContractCompat.WatchNextPrograms.TYPE_MOVIE
        } else {
            TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
        }

        val builder = WatchNextProgram.Builder()
            .setType(programType)
            .setWatchNextType(watchNextType.contractValue)
            .setTitle(progress.name)
            .setLastEngagementTimeUtcMillis(item.engagementTimeMs)
            .setInternalProviderId(watchNextId(progress))
            .setIntentUri(buildPlayUri(progress))

        builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)

        val horizontalArt = (progress.backdrop ?: progress.poster)?.takeUnless(PlexArtworkLink::needsToken)
        horizontalArt?.let {
            val uriWithCacheBuster = Uri.parse(it).buildUpon()
                .appendQueryParameter("v", "horizontal_fix")
                .build()
            builder.setPosterArtUri(uriWithCacheBuster)
        }

        // Only a real duration is published. A percent-only resume used to get an invented 100,000 ms
        // duration here, which made the launcher draw a progress bar for a made-up length.
        if (progress.duration > 0) {
            builder.setDurationMillis(progress.duration.toInt())
            if (positionMs > 0L) builder.setLastPlaybackPositionMillis(positionMs.toInt())
        }

        if (!isMovie) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build()
    }

    /**
     * Writes [program] per [WatchNextWritePolicy]: RECONCILE updates an existing row in place (a row the
     * launcher hid stays hidden); PLAYED replaces it so a hidden row becomes visible again.
     */
    fun upsertWatchNextProgram(
        program: WatchNextProgram,
        internalId: String,
        source: WatchNextWritePolicy.WriteSource = WatchNextWritePolicy.WriteSource.RECONCILE
    ) {
        try {
            val existingId = findWatchNextByInternalId(internalId)
            when (WatchNextWritePolicy.modeFor(source, existingId != null)) {
                WatchNextWritePolicy.WriteMode.REPLACE -> replaceWatchNextProgram(program, internalId)
                WatchNextWritePolicy.WriteMode.UPDATE -> {
                    val values = program.toContentValues()
                    // An UPDATE leaves unset columns alone, so clear a duration/position that is no longer
                    // known instead of keeping a stale (or previously invented) one.
                    if (!values.containsKey("duration_millis")) values.putNull("duration_millis")
                    if (!values.containsKey("last_playback_position_millis")) values.putNull("last_playback_position_millis")
                    val uri = TvContractCompat.buildWatchNextProgramUri(requireNotNull(existingId))
                    context.contentResolver.update(uri, values, null, null)
                }
                WatchNextWritePolicy.WriteMode.INSERT -> insertWatchNext(program)
            }
        } catch (_: Exception) {
        }
    }

    /** Deletes every row with [internalId], then inserts a fresh one (visible even if the launcher hid the old). */
    fun replaceWatchNextProgram(program: WatchNextProgram, internalId: String) {
        try {
            findAllWatchNextByInternalId(internalId).forEach { rowId ->
                context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(rowId), null, null)
            }
            insertWatchNext(program)
        } catch (_: Exception) {
        }
    }

    private fun insertWatchNext(program: WatchNextProgram) {
        context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues())
    }

    private fun findAllWatchNextByInternalId(internalId: String): List<Long> {
        val ids = mutableListOf<Long>()
        context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(
                TvContractCompat.WatchNextPrograms._ID,
                TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
            ),
            null, null, null
        )?.use {
            val pIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            val idIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
            if (pIdx >= 0 && idIdx >= 0) {
                while (it.moveToNext()) if (it.getString(pIdx) == internalId) ids.add(it.getLong(idIdx))
            }
        }
        return ids
    }

    fun removeWatchNextProgram(internalId: String) {
        try {
            val existingId = findWatchNextByInternalId(internalId) ?: return
            val uri = TvContractCompat.buildWatchNextProgramUri(existingId)
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    fun removeWatchNextByContentId(contentId: String) {
        try {
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val idIdx = it.getColumnIndex(
                        TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                    )
                    if (idIdx >= 0) {
                        val providerId = it.getString(idIdx)
                        if (watchNextIdMatchesContentId(providerId, contentId)) {
                            val pkIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                            if (pkIdx >= 0) {
                                val uri = TvContractCompat.buildWatchNextProgramUri(it.getLong(pkIdx))
                                context.contentResolver.delete(uri, null, null)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    fun getAllWatchNextInternalIds(): Set<String> {
        val result = mutableSetOf<String>()
        try {
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
                null, null, null
            )
            cursor?.use {
                val idIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                if (idIdx >= 0) {
                    while (it.moveToNext()) {
                        val providerId = it.getString(idIdx)
                        if (providerId?.startsWith("wn_") == true) {
                            result.add(providerId)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun clearAllWatchNextPrograms() {
        var cursor: android.database.Cursor? = null
        try {
            cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )
            cursor?.let {
                while (it.moveToNext()) {
                    val idIdx = it.getColumnIndex(
                        TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                    )
                    if (idIdx >= 0) {
                        val providerId = it.getString(idIdx)
                        if (providerId?.startsWith("wn_") == true) {
                            val pkIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                            if (pkIdx >= 0) {
                                val uri = TvContractCompat.buildWatchNextProgramUri(it.getLong(pkIdx))
                                context.contentResolver.delete(uri, null, null)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
    }

    private fun findWatchNextByInternalId(internalId: String): Long? {
        return try {
            val cursor = context.contentResolver.query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                arrayOf(
                    TvContractCompat.WatchNextPrograms._ID,
                    TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null,
                null,
                null
            )
            var foundId: Long? = null
            cursor?.use {
                while (it.moveToNext()) {
                    val providerIdIdx = it.getColumnIndex(
                        TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID
                    )
                    if (providerIdIdx >= 0) {
                        val currentProviderId = it.getString(providerIdIdx)
                        if (currentProviderId == internalId) {
                            val idIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                            if (idIdx >= 0) {
                                foundId = it.getLong(idIdx)
                                break
                            }
                        }
                    }
                }
            }
            foundId
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPlayUri(progress: WatchProgress): Uri =
        Uri.parse(
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("contentId", progress.contentId)
                putExtra("contentType", progress.contentType)
                putExtra("videoId", progress.videoId)
                putExtra("name", progress.name)
                putExtra("poster", progress.poster)
                putExtra("backdrop", progress.backdrop)
                putExtra("logo", progress.logo)
                progress.season?.let { putExtra("season", it) }
                progress.episode?.let { putExtra("episode", it) }
                progress.episodeTitle?.let { putExtra("episodeTitle", it) }
                putExtra("launchMode", "stream")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }.toUri(Intent.URI_INTENT_SCHEME)
        )
}

internal fun watchNextIdMatchesContentId(providerId: String?, contentId: String): Boolean {
    val baseId = "wn_$contentId"
    if (providerId == baseId) return true
    if (providerId?.startsWith("${baseId}_s") != true) return false

    val episodeSeparator = providerId.indexOf('e', startIndex = baseId.length + 2)
    return episodeSeparator > baseId.length + 2 &&
        episodeSeparator < providerId.lastIndex &&
        (baseId.length + 2 until episodeSeparator).all { providerId[it].isDigit() } &&
        (episodeSeparator + 1..providerId.lastIndex).all { providerId[it].isDigit() }
}
