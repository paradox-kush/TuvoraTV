package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.PlaybackResumeOffer
import com.nuvio.tv.core.contracts.PlaybackResumeOfferSource
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.client.mediabrowser.IsoTime
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.policy.PlaybackDecisionPolicy
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import kotlinx.coroutines.CancellationException

/**
 * "Resume from server" (D3, design 5.7 / 5.12): Tuvora resumes from ITS record, as for every source; when the
 * server's own position is newer (the viewer watched elsewhere) the player is told so it can offer the jump. One
 * item fetch at play start (the item carries `UserData`), decided by [PlaybackDecisionPolicy.resumeOffer].
 */
internal class MediaServerResumeOffers(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
) : PlaybackResumeOfferSource {
    override val name: String = "mediaserver"

    override fun handles(videoId: String, providerAddonId: String?): Boolean =
        MediaServerIds.isContentId(videoId) && MediaServerIds.parse(videoId)?.kind.let { it == MediaServerIds.Kind.MOVIE || it == MediaServerIds.Kind.EPISODE }

    override suspend fun offer(videoId: String, tuvoraPositionMs: Long?, tuvoraUpdatedAtMs: Long?, durationMs: Long?): PlaybackResumeOffer? {
        val parsed = MediaServerIds.parse(videoId) ?: return null
        val entry = store.entryByServerKey(parsed.serverKey) ?: return null
        val client = services.clientFor(entry) ?: return null
        val item = try {
            client.item(parsed.itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            return null
        } catch (e: MediaServerException) {
            return null
        } ?: return null
        val data = item.userData ?: return null
        val offer = PlaybackDecisionPolicy.resumeOffer(
            serverPositionMs = data.playbackPositionTicks.takeIf { it > 0 }?.let(PlaybackDecisionPolicy::ticksToMs),
            serverLastPlayedAtMs = IsoTime.parse(data.lastPlayedDate),
            tuvoraPositionMs = tuvoraPositionMs,
            tuvoraUpdatedAtMs = tuvoraUpdatedAtMs,
            durationMs = durationMs ?: item.runTimeTicks?.let(PlaybackDecisionPolicy::ticksToMs),
        ) ?: return null
        return PlaybackResumeOffer(offer.serverPositionMs, autoStart = offer.startAutomatically)
    }
}
