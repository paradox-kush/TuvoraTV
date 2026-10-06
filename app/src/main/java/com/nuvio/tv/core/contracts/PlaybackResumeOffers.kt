package com.nuvio.tv.core.contracts

import kotlinx.coroutines.CancellationException

/** "You were further along elsewhere": the position the player may offer to jump to. */
data class PlaybackResumeOffer(val positionMs: Long)

/**
 * A source that keeps its own resume position and can say when it is NEWER than Tuvora's (a media server the
 * viewer also watches from another app - D3). The player asks once when playback of that source starts; nothing
 * about the answer is applied silently. One request at most, never a poll.
 */
interface PlaybackResumeOfferSource {
    val name: String

    fun handles(videoId: String, providerAddonId: String?): Boolean

    /** Null = nothing to offer (including on any failure). */
    suspend fun offer(videoId: String, tuvoraPositionMs: Long?, tuvoraUpdatedAtMs: Long?, durationMs: Long?): PlaybackResumeOffer?
}

object PlaybackResumeOfferRegistry {
    private val sources = NamedRegistry<PlaybackResumeOfferSource>("PlaybackResumeOfferSource")

    fun register(source: PlaybackResumeOfferSource) = sources.register(source.name, source)

    val isEmpty: Boolean get() = sources.isEmpty

    fun handles(videoId: String, providerAddonId: String?): Boolean = sources.all.any { it.handles(videoId, providerAddonId) }

    suspend fun offerFor(
        videoId: String,
        providerAddonId: String?,
        tuvoraPositionMs: Long?,
        tuvoraUpdatedAtMs: Long?,
        durationMs: Long?,
    ): PlaybackResumeOffer? {
        for (source in sources.all) {
            if (!source.handles(videoId, providerAddonId)) continue
            try {
                source.offer(videoId, tuvoraPositionMs, tuvoraUpdatedAtMs, durationMs)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // an offer is a courtesy: its failure must never reach playback
            }
        }
        return null
    }

    fun resetForTest() = sources.resetForTest()
}
