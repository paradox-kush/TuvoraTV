package com.nuvio.tv.ui.screens.iptv.player

import com.nuvio.tv.core.contracts.CatchUpDialectRetry
import com.nuvio.tv.core.contracts.CatchUpPlaybackPort
import com.nuvio.tv.core.contracts.IptvContentClassifier
import com.nuvio.tv.core.contracts.LivePlayback
import com.nuvio.tv.core.contracts.PlaybackDnsPort
import com.nuvio.tv.core.contracts.PreparedLive
import com.nuvio.tv.core.contracts.CatchUpContinuation
import com.nuvio.tv.core.contracts.CatchUpProgramme
import com.nuvio.tv.core.contracts.CatchUpRestart
import com.nuvio.tv.core.iptv.CatchUpNextProgramme
import com.nuvio.tv.core.iptv.CatchUpPlaybackCoordinator
import com.nuvio.tv.core.iptv.epg.XmltvClient
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.dns.PlaylistDnsResolver
import okhttp3.Dns
import javax.inject.Inject

/**
 * The fork's implementation of the neutral [LivePlayback] ports: thin adapters over the existing
 * IPTV services. This file lives on a fork path (ui/screens/iptv), so it may name core.iptv freely;
 * the controller only ever sees the neutral contract. See research/tv-player-mpv-engine-ownership.md
 * Part B — B1 wires the seam without moving logic (the services below are unchanged).
 */
class IptvLivePlayback @Inject constructor(
    private val dnsResolver: PlaylistDnsResolver,
    private val catchUpCoordinator: CatchUpPlaybackCoordinator,
    private val xmltv: XmltvClient,
    private val contentDb: com.nuvio.tv.core.iptv.content.IptvContentDb,
) : LivePlayback {

    override val classifier: IptvContentClassifier = object : IptvContentClassifier {
        override fun isLiveId(id: String): Boolean = XtreamItemRegistry.isLiveContentId(id)
        override fun refreshableIptvId(candidates: List<String?>): String? =
            candidates.filterNotNull().firstOrNull { it.startsWith(XtreamItemRegistry.PREFIX) }
    }

    override val dns: PlaybackDnsPort = object : PlaybackDnsPort {
        override fun prepareLive(contentId: String?, rawUrl: String): PreparedLive {
            val prepared = dnsResolver.prepareLive(contentId, rawUrl)
            return PreparedLive(url = prepared.url, headers = prepared.headers)
        }
        override fun dnsForVideoId(videoId: String?): Dns? = dnsResolver.dnsForVideoId(videoId)
    }

    override val catchUp: CatchUpPlaybackPort = object : CatchUpPlaybackPort {
        override fun onPlayed(contentId: String?) = catchUpCoordinator.onPlayed(contentId)
        override fun onFailed(contentId: String?, errorCode: Int): CatchUpDialectRetry? =
            catchUpCoordinator.onFailed(contentId, errorCode)
                ?.let { CatchUpDialectRetry(channelName = it.channelName, url = it.url) }

        override fun continuation(contentId: String?): CatchUpContinuation? {
            val ctx = catchUpCoordinator.replayContextFor(contentId) ?: return null
            return CatchUpContinuation(
                windowEndMs = ctx.windowEndMs,
                programme = CatchUpProgramme("${ctx.channelName} · ${ctx.programmeTitle}", ctx.startMs, ctx.programmeEndMs),
            )
        }

        override suspend fun programmeAt(contentId: String?, atMs: Long): CatchUpProgramme? {
            val ctx = catchUpCoordinator.replayContextFor(contentId) ?: return null
            val from = atMs - LOOKAHEAD_MS
            val to = atMs + LOOKAHEAD_MS
            // An Xtream channel's past lives in its own per-channel history table (the guide's
            // "sid:" key); an M3U channel's, like an Xtream channel without one, in the XMLTV lane.
            val history = if (ctx.request != null) {
                runCatching { contentDb.epgWindow(ctx.account.id, "sid:${ctx.streamId}", from, to) }.getOrDefault(emptyList())
            } else emptyList()
            val stored = history.ifEmpty { xmltv.storedWindow(ctx.account, ctx.streamId, from, to) }
            val rows = stored.map { CatchUpNextProgramme.Row(it.title, it.startMs, it.endMs) }
            return CatchUpNextProgramme.pick(rows, atMs)
                ?.let { CatchUpProgramme("${ctx.channelName} · ${it.title}", it.startMs, it.endMs) }
        }

        override fun restartAt(contentId: String?, atMs: Long): CatchUpRestart? =
            catchUpCoordinator.restartReplay(contentId, atMs, System.currentTimeMillis())
                ?.let { CatchUpRestart(it.url, it.startMs, it.windowEndMs) }

        override fun hold(contentId: String?) = catchUpCoordinator.hold(contentId)

        override fun liveUrl(contentId: String?): String? = catchUpCoordinator.replayContextFor(contentId)?.liveUrl
    }

    private companion object {
        /** How far either side of the playhead the next-programme lookup reads. */
        const val LOOKAHEAD_MS = 6L * 60 * 60 * 1000
    }
}
