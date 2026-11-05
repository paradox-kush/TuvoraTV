package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackNetworkRequest
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.core.TransientLoadRetryPolicy

/**
 * Whether a provider link may be re-opened by the engine that plays it.
 *
 * A Stalker live link comes from `create_link` and carries a single-use, short-TTL play token: the
 * first open spends it. Re-opening the same URL after a drop cannot succeed, and each attempt costs
 * the viewer a request and, on `max_connections=1` lines, a connection slot. So such a link is
 * SESSION_ONLY: libmpv gets `reconnect=0` (MpvAdapterPlan), Media3 gets no OkHttp connection retry
 * and no in-stack load retry (AndroidMedia3Backend), and only PlaybackSession recovers, by asking the
 * resolver for a freshly minted link at its own bounded cadence.
 *
 * A static Stalker row (StalkerPlaybackLinkPolicy.Static) is marked too: the resolver cannot tell it
 * from a minted one, and a session reconnect re-mints for it anyway (forceFresh), so the engine-level
 * re-open saved nothing. Xtream and M3U live URLs are reusable and keep the engines' bounded
 * transient recovery. VOD keeps engine defaults: a seek re-requests the same URL by design.
 */
internal object ProviderLinkReusePolicy {

    fun isSingleUse(sourceType: ProviderSourceType, contentType: ContentType): Boolean =
        sourceType == ProviderSourceType.STALKER && contentType == ContentType.LIVE

    fun network(sourceType: ProviderSourceType, contentType: ContentType): PlaybackNetworkRequest =
        if (isSingleUse(sourceType, contentType)) SINGLE_USE else PlaybackNetworkRequest()

    private val SINGLE_USE = PlaybackNetworkRequest(
        retryConnectionFailures = false,
        transientLoadRetryPolicy = TransientLoadRetryPolicy.SESSION_ONLY,
    )
}
