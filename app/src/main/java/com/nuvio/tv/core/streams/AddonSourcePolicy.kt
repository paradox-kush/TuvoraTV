package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Stream

/**
 * Store builds (AppFeaturePolicy.addonStreamSourcesEnabled = false) keep add-ons for DISCOVERY —
 * catalogs, metadata, subtitles — but never use them as PLAYBACK SOURCES. Add-ons sync onto store
 * devices from tuvora.co and full builds, so without this gate a Play Store build would silently
 * play streams from a synced add-on. Pure decisions only; callers pass the build flag in.
 */
object AddonSourcePolicy {
    private const val STREAM_RESOURCE = "stream"

    /**
     * The manifest as this build may use it. When stream sources are disabled every resource named
     * "stream" is removed, so every "does this add-on serve streams?" check downstream (stream fetch,
     * in-player sources, autoplay add-on lists, playback availability) sees none. Returns the same
     * instance when nothing changes.
     */
    fun manifestForBuild(addon: Addon, streamSourcesEnabled: Boolean): Addon {
        if (streamSourcesEnabled) return addon
        if (addon.resources.none { it.isStreamResource() }) return addon
        return addon.copy(resources = addon.resources.filterNot { it.isStreamResource() })
    }

    /**
     * Streams embedded in an add-on meta (`meta.videos[].streams`). Dropped when stream sources are
     * disabled, except IPTV ones, which are the user's own provider and always playable.
     */
    fun embeddedStreamsForBuild(
        streams: List<Stream>,
        streamSourcesEnabled: Boolean,
        isIptv: Boolean
    ): List<Stream> = if (streamSourcesEnabled || isIptv) streams else emptyList()

    /**
     * Whether a "reuse last link" cache entry may be replayed. When stream sources are disabled only
     * IPTV links are replayed; anything else — an add-on link, or a legacy entry saved before this
     * gate — fails closed. TV cache entries carry no source identity, so IPTV-ness is decided by the
     * caller from the content id.
     */
    fun cachedLinkUsable(streamSourcesEnabled: Boolean, isIptv: Boolean): Boolean =
        streamSourcesEnabled || isIptv

    private fun com.nuvio.tv.domain.model.AddonResource.isStreamResource(): Boolean =
        name.trim().equals(STREAM_RESOURCE, ignoreCase = true)
}
