package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.PlaybackPlayMethod
import com.nuvio.tv.core.contracts.StreamSourceGroup
import com.nuvio.tv.core.contracts.StreamSourceProvider
import com.nuvio.tv.core.mediaserver.MsLog
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.client.PlaybackInfoRequest
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserUrls
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaSourceDto
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.policy.PlaybackDecisionPolicy
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.CancellationException

/**
 * The media-server lane of the stream-source port (design 5.5), TV edition: a server's own movie / episode
 * resolves to deferred streams - one per `MediaSource` ("1080p · HEVC · 4.2 GB"), each
 * `ms-deferred:{serverKey}|{itemId}|{mediaSourceId}` - and the real play URL is minted at pick time from the
 * server's PlaybackInfo through [PlaybackDecisionPolicy]. The list never holds a token or a playable URL.
 * The matched lane (a TMDB title found on a server) is P3: [matchSourceGroups] is empty until then.
 */
internal class MediaServerStreamSourceProvider(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
    /** Rebuilds an item's registry record after a cold start (Continue Watching / a deep link): one item fetch. */
    private val ensureRegistered: suspend (id: String) -> Boolean = { false },
) : StreamSourceProvider {
    private val log = MsLog.withTag("MediaServerStreamSource")

    override fun isHandledId(videoId: String?): Boolean = MediaServerIds.isContentId(videoId)

    override suspend fun directStreams(videoId: String): List<AddonStreams> {
        var item = MediaServerItemRegistry.get(videoId)
        if (item == null || (item.sources.isEmpty() && item.kind != MediaServerIds.Kind.SERIES)) {
            if (ensureRegistered(videoId)) item = MediaServerItemRegistry.get(videoId)
        }
        if (item == null) return emptyList()
        val entry = store.entryByServerKey(item.serverKey) ?: return emptyList()
        val sources = item.sources.ifEmpty { listOf(MediaServerItemRegistry.Source(id = "", label = "Direct play", container = null)) }
        // Emby authenticates a player request by header; Jellyfin's direct stream needs none (design 5.5).
        val headers = authHeaders(entry, services)
        val streams = sources.map { source ->
            Stream(
                name = source.label,
                title = item.name,
                description = null,
                url = MediaServerIds.deferredUrl(item.serverKey, item.itemId, source.id.takeIf { it.isNotBlank() }),
                ytId = null,
                infoHash = null,
                fileIdx = null,
                externalUrl = null,
                behaviorHints = StreamBehaviorHints(
                    notWebReady = null,
                    bingeGroup = null,
                    countryWhitelist = null,
                    proxyHeaders = headers?.let { ProxyHeaders(request = it, response = null) },
                ),
                addonName = entry.name,
                addonLogo = null,
                // Sidecar text subtitles ride with the stream; the engines list the container's own tracks by themselves.
                subtitles = source.subtitles.map { sub ->
                    Subtitle(
                        id = "${item.itemId}:${source.id}:${sub.index}",
                        url = MediaBrowserUrls.subtitle(entry.address.orEmpty(), item.itemId, source.id, sub.index),
                        lang = sub.language,
                        addonName = entry.name,
                        addonLogo = null,
                        isStreamProvided = true,
                        headers = headers,
                    )
                },
                sourceId = MediaServerIds.DIRECT_GROUP_ID,
            )
        }
        return listOf(AddonStreams(addonName = entry.name, addonLogo = null, streams = streams))
    }

    // The matched lane is P3 (design 7): no match sources yet.
    override fun matchSourceGroups(type: String): List<StreamSourceGroup> = emptyList()

    override suspend fun resolveMatchStreams(sourceId: String, type: String, videoId: String, season: Int?, episode: Int?): List<Stream> = emptyList()

    override fun isMatchSourceId(providerAddonId: String): Boolean = providerAddonId.startsWith(MediaServerIds.MATCH_GROUP_PREFIX)

    override fun isDeferredUrl(url: String?): Boolean = MediaServerIds.isDeferredUrl(url)

    /**
     * Mints the play URL. [forceMint] means the previous attempt FAILED (the player's credential-refresh gate
     * re-asks): direct play is then treated as failed and the server's transcode is chosen. A revoked token
     * (401/403) drops the session so Settings shows "sign in again" - and returns null (no mint loop).
     */
    override suspend fun resolveDeferredUrl(url: String, forceMint: Boolean): String? {
        val deferred = MediaServerIds.parseDeferred(url) ?: return null
        val entry = store.entryByServerKey(deferred.serverKey) ?: return null
        val address = entry.address?.takeIf { it.isNotBlank() } ?: return null
        val client = services.clientFor(entry) ?: return null
        return try {
            val negotiation = client.playbackInfo(deferred.itemId, PlaybackInfoRequest(mediaSourceId = deferred.mediaSourceId, forceTranscode = forceMint))
            val chosen = negotiation.sources.firstOrNull { s -> deferred.mediaSourceId != null && s.id.equals(deferred.mediaSourceId, ignoreCase = true) }
                ?: negotiation.sources.firstOrNull()
                ?: return null
            val decision = PlaybackDecisionPolicy.decide(chosen.toFacts(), userBitrateCap = null, directPlayFailed = forceMint)
            val minted = when (val plan = decision.plan) {
                is PlaybackDecisionPolicy.Plan.StaticStream ->
                    MediaBrowserUrls.directStream(address, deferred.itemId, plan.mediaSourceId ?: chosen.id, chosen.container, negotiation.playSessionId)
                is PlaybackDecisionPolicy.Plan.ServerUrl -> MediaBrowserUrls.resolve(address, plan.pathOrUrl)
                is PlaybackDecisionPolicy.Plan.NotPlayable -> return null
            }
            MediaServerPlaybackSessions.record(
                MediaServerPlaybackSessions.Session(
                    serverKey = deferred.serverKey,
                    itemId = deferred.itemId,
                    mediaSourceId = chosen.id,
                    playSessionId = negotiation.playSessionId,
                    playMethod = decision.method ?: PlaybackPlayMethod.DIRECT_PLAY,
                ),
            )
            minted
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaServerException.Http) {
            if (e.isUnauthorized) services.onUnauthorized(entry.serverKey)
            log.w { "mint failed: HTTP ${e.status}" }
            null
        } catch (e: MediaServerException) {
            log.w { "mint failed: ${e::class.simpleName}" }
            null
        }
    }

    private fun MediaSourceDto.toFacts() = PlaybackDecisionPolicy.SourceFacts(
        id = id,
        protocol = protocol,
        container = container,
        supportsDirectPlay = supportsDirectPlay,
        supportsDirectStream = supportsDirectStream,
        supportsTranscoding = supportsTranscoding,
        directStreamUrl = directStreamUrl,
        transcodingUrl = transcodingUrl,
    )
}
