package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.PlaybackPlayMethod
import com.nuvio.tv.core.contracts.StreamSourceGroup
import com.nuvio.tv.core.contracts.StreamSourceProvider
import com.nuvio.tv.core.mediaserver.MsLog
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.client.PlaybackInfoRequest
import com.nuvio.tv.core.mediaserver.client.PlaybackNegotiation
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserUrls
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaSourceDto
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.policy.MintFailurePolicy
import com.nuvio.tv.core.mediaserver.policy.PlaybackDecisionPolicy
import com.nuvio.tv.core.mediaserver.policy.ServerAudioChoicePolicy
import com.nuvio.tv.core.mediaserver.policy.VersionPickPolicy
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException

/**
 * The media-server lane of the stream-source port (design 5.5), TV edition: a server's own movie / episode
 * resolves to deferred streams - one per `MediaSource` ("1080p · HEVC · 4.2 GB"), each
 * `ms-deferred:{serverKey}|{itemId}|{mediaSourceId}` - and the real play URL is minted at pick time from the
 * server's PlaybackInfo through [PlaybackDecisionPolicy]. The list never holds a token or a playable URL.
 * The matched lane (a TMDB title found on a server, `ms-match:{serverKey}` groups) is [MediaServerMatchLane]'s; its
 * streams are the same deferred shape and mint through the same path.
 */
internal class MediaServerStreamSourceProvider(
    private val store: MediaServerEntryStore,
    private val services: MediaServerServices,
    /** Rebuilds an item's registry record after a cold start (Continue Watching / a deep link): one item fetch. */
    private val ensureRegistered: suspend (id: String) -> Boolean = { false },
    /** The matched lane (a TMDB title found on a server, `ms-match:{serverKey}` groups); null = direct lane only. */
    private val matchLane: MediaServerMatchLane? = null,
    /** Tuvora's audio-language preference for server-built streams; null = leave the server's choice alone. */
    private val audioPreference: ServerAudioChoicePolicy.Preference? = null,
    /** Told why a mint failed, so the viewer hears it instead of a silent return to the list (the app shows a toast). */
    private val onMintFailure: (MintFailurePolicy.Reason) -> Unit = {},
) : StreamSourceProvider {
    private val log = MsLog.withTag("MediaServerStreamSource")

    override fun isHandledId(videoId: String?): Boolean = MediaServerIds.isContentId(videoId)

    override suspend fun directStreams(videoId: String): List<AddonStreams> {
        var item = MediaServerItemRegistry.get(videoId)
        if (item == null || (!item.sourcesLoaded && item.sources.isEmpty() && item.kind != MediaServerIds.Kind.SERIES)) {
            if (!ensureRegistered(videoId)) return emptyList()
            item = MediaServerItemRegistry.get(videoId)
        }
        if (item == null) return emptyList()
        val entry = store.entryByServerKey(item.serverKey) ?: return emptyList()
        // Emby authenticates a player request by header; Jellyfin's direct stream needs none (design 5.5).
        val headers = authHeaders(entry, services)
        val streams = item.sources.ifEmpty { listOf(null) }.map { source ->
            MediaServerStreamItems.build(entry, item.itemId, item.name, source, MediaServerIds.DIRECT_GROUP_ID, headers)
        }
        return listOf(AddonStreams(addonName = entry.name, addonLogo = null, streams = streams))
    }

    // The matched lane (design 5.6, P3): a TMDB/IMDb title page offers each signed-in server that has the title.
    override fun matchSourceGroups(type: String): List<StreamSourceGroup> = matchLane?.groups(type).orEmpty()

    override suspend fun resolveMatchStreams(sourceId: String, type: String, videoId: String, season: Int?, episode: Int?): List<Stream> =
        matchLane?.streams(sourceId, type, videoId, season, episode).orEmpty()

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
            suspend fun negotiate(audioStreamIndex: Int?, forceTranscode: Boolean = forceMint) = client.playbackInfo(
                deferred.itemId,
                PlaybackInfoRequest(mediaSourceId = deferred.mediaSourceId, audioStreamIndex = audioStreamIndex, forceTranscode = forceTranscode),
            )
            fun pick(n: PlaybackNegotiation) = if (deferred.mediaSourceId != null)
                n.sources.firstOrNull { s -> s.id.equals(deferred.mediaSourceId, ignoreCase = true) }
                else n.sources.firstOrNull()
            fun usable(s: MediaSourceDto?) = s != null && !MintFailurePolicy.isServerPlaceholder(s.path) && !VersionPickPolicy.isPlaceholder(s.type)
            var retryTranscode = forceMint
            var negotiation = negotiate(null)
            var chosen = pick(negotiation)
            if (chosen == null && forceMint) {
                negotiation = negotiate(null, forceTranscode = false)
                retryTranscode = false
                chosen = pick(negotiation)
            }
            if (!usable(chosen)) return fail(MintFailurePolicy.noPlayableSource)
            var decision = PlaybackDecisionPolicy.decide(chosen!!.toFacts(), userBitrateCap = null, directPlayFailed = retryTranscode, supportsStaticHttp = entry.type.wire == "jellyfin")
            if (decision.plan is PlaybackDecisionPolicy.Plan.NotPlayable && retryTranscode) {
                // The retry asked the server to transcode and it cannot (a direct-play-only deployment answers with nothing
                // playable): play the original again rather than give up - the failure may have been transient.
                val again = negotiate(null, forceTranscode = false)
                val source = pick(again)
                if (usable(source)) {
                    val directAgain = PlaybackDecisionPolicy.decide(source!!.toFacts(), userBitrateCap = null, directPlayFailed = false, supportsStaticHttp = entry.type.wire == "jellyfin")
                    if (directAgain.plan !is PlaybackDecisionPolicy.Plan.NotPlayable) { negotiation = again; chosen = source; decision = directAgain }
                }
            }
            // A stream the server builds carries ONE audio track and the player cannot switch it: ask for the one Tuvora's
            // language preferences pick (the server's default stands when none matches). Direct play needs no asking - the
            // player sees every track.
            val preference = audioPreference
            if (preference != null && decision.method == PlaybackPlayMethod.TRANSCODE) {
                val wanted = preference.languages()
                val tracks = chosen!!.mediaStreams.filter { it.type.equals("Audio", ignoreCase = true) }.map { ServerAudioChoicePolicy.Track(it.index, it.language) }
                val index = ServerAudioChoicePolicy.choose(tracks, chosen.defaultAudioStreamIndex, wanted, preference.matches)
                if (index != null) {
                    val asked = negotiate(index)
                    val askedSource = pick(asked)
                    if (askedSource != null) {
                        val askedDecision = PlaybackDecisionPolicy.decide(askedSource.toFacts(), userBitrateCap = null, directPlayFailed = retryTranscode, supportsStaticHttp = entry.type.wire == "jellyfin")
                        if (askedDecision.plan !is PlaybackDecisionPolicy.Plan.NotPlayable) { negotiation = asked; chosen = askedSource; decision = askedDecision }
                    }
                }
            }
            val picked = chosen!!
            val minted = when (val plan = decision.plan) {
                is PlaybackDecisionPolicy.Plan.StaticStream ->
                    MediaBrowserUrls.directStream(address, deferred.itemId, plan.mediaSourceId ?: picked.id, picked.container, negotiation.playSessionId)
                is PlaybackDecisionPolicy.Plan.ServerUrl -> MediaBrowserUrls.resolve(address, plan.pathOrUrl)
                is PlaybackDecisionPolicy.Plan.NotPlayable -> return fail(MintFailurePolicy.noPlayableSource)
            }
            MediaServerPlaybackSessions.record(
                MediaServerPlaybackSessions.Session(
                    serverKey = deferred.serverKey,
                    itemId = deferred.itemId,
                    mediaSourceId = picked.id,
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
            fail(MintFailurePolicy.forException(e))
        } catch (e: MediaServerException) {
            log.w { "mint failed: ${e::class.simpleName}" }
            fail(MintFailurePolicy.forException(e))
        }
    }

    /** Tells the viewer why (never the URL or a token), then the null every caller already handles. */
    private fun fail(reason: MintFailurePolicy.Reason): String? {
        onMintFailure(reason)
        return null
    }

    /** The recovery path: the media source this play was minted for (kept in [MediaServerPlaybackSessions]), minted again. */
    override suspend fun reissueLink(videoId: String, forceMint: Boolean): String? {
        val parsed = MediaServerIds.parse(videoId) ?: return null
        val session = MediaServerPlaybackSessions.forItem(parsed.serverKey, parsed.itemId)
        return resolveDeferredUrl(MediaServerIds.deferredUrl(parsed.serverKey, parsed.itemId, session?.mediaSourceId), forceMint)
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
