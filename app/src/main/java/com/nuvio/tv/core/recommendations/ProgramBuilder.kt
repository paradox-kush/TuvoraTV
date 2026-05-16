package com.nuvio.tv.core.recommendations

import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.nuvio.tv.domain.model.WatchProgress
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

    fun buildWatchNextProgram(progress: WatchProgress): WatchNextProgram {
        val isMovie = progress.contentType == "movie"
        val programType = if (isMovie) {
            TvContractCompat.WatchNextPrograms.TYPE_MOVIE
        } else {
            TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
        }

        val builder = WatchNextProgram.Builder()
            .setType(programType)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setTitle(progress.name)
            .setLastEngagementTimeUtcMillis(progress.lastWatched)
            .setInternalProviderId(watchNextId(progress))
            .setIntentUri(buildPlayUri(progress))

        builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)

        val horizontalArt = progress.backdrop ?: progress.poster
        horizontalArt?.let {
            val uriWithCacheBuster = Uri.parse(it).buildUpon()
                .appendQueryParameter("v", "horizontal_fix")
                .build()
            builder.setPosterArtUri(uriWithCacheBuster)
        }

        if (progress.duration > 0) {
            builder.setLastPlaybackPositionMillis(progress.position.toInt())
            builder.setDurationMillis(progress.duration.toInt())
        }

        if (!isMovie) {
            progress.season?.let { builder.setSeasonNumber(it) }
            progress.episode?.let { builder.setEpisodeNumber(it) }
            progress.episodeTitle?.let { builder.setEpisodeTitle(it) }
        }

        return builder.build()
    }

    fun upsertWatchNextProgram(program: WatchNextProgram, internalId: String) {
        try {
            val existingId = findWatchNextByInternalId(internalId)
            if (existingId != null) {
                val uri = TvContractCompat.buildWatchNextProgramUri(existingId)
                context.contentResolver.update(uri, program.toContentValues(), null, null)
            } else {
                context.contentResolver.insert(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    program.toContentValues()
                )
            }
        } catch (_: Exception) {
        }
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
                        if (providerId != null && providerId.startsWith("wn_${contentId}")) {
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
        Uri.Builder()
            .scheme(RecommendationConstants.DEEP_LINK_SCHEME)
            .authority(RecommendationConstants.DEEP_LINK_HOST)
            .appendPath(RecommendationConstants.DEEP_LINK_PATH_PLAY)
            .appendPath(progress.contentId)
            .appendQueryParameter(RecommendationConstants.PARAM_CONTENT_TYPE, progress.contentType)
            .appendQueryParameter(RecommendationConstants.PARAM_VIDEO_ID, progress.videoId)
            .appendQueryParameter(RecommendationConstants.PARAM_NAME, progress.name)
            .apply {
                progress.season?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_SEASON, it.toString())
                }
                progress.episode?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_EPISODE, it.toString())
                }
                appendQueryParameter(
                    RecommendationConstants.PARAM_RESUME_POSITION,
                    progress.position.toString()
                )
                progress.poster?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_POSTER, it)
                }
                progress.backdrop?.let {
                    appendQueryParameter(RecommendationConstants.PARAM_BACKDROP, it)
                }
            }
            .build()
}
