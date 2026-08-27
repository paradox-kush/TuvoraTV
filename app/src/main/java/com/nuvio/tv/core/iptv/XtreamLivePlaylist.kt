package com.nuvio.tv.core.iptv

import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.playback.core.ProviderSelectionId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ordered channel list currently being watched, so the fullscreen player can zap
 * up/down to the next/previous channel (TiViMate-style). The guide sets it before launching
 * a channel; the player reads the neighbour of the channel it's on.
 *
 * ponytail: a single process-lifetime list — only one live session is active at a time.
 */
@Singleton
class XtreamLivePlaylist @Inject constructor() {
    @Volatile private var snapshot = PlaylistSnapshot(version = 0, channels = emptyList())

    @Synchronized
    fun set(list: List<LiveChannelRef>) {
        check(snapshot.version < Long.MAX_VALUE) { "Live playlist version exhausted" }
        snapshot = PlaylistSnapshot(snapshot.version + 1, list.toList())
    }

    /** Display-only lookup for the exact current immutable playlist snapshot. */
    fun presentationFor(contentId: String): LiveChannelPresentation? {
        val current = snapshot
        return current.channels.firstOrNull { it.id == contentId }
            ?.toPresentation(current.version)
    }

    /** Display-only neighbor from one immutable snapshot; no transport value crosses the result. */
    fun relativePresentation(contentId: String, delta: Int): LiveChannelPresentation? {
        val current = snapshot
        return current.relativeTo(contentId, delta)?.toPresentation(current.version)
    }

    /**
     * The channel [delta] steps from [contentId] (e.g. +1 = next, -1 = previous), or null if
     * there is no list or the channel is not in it.
     *
     * Wraps at both ends: the list is a ring, and stopping dead on the last channel reads as a
     * broken remote. Every live entry point (guide, search, sports) hands off to the same player,
     * so one D-pad press means the same thing however the viewer arrived.
     */
    fun relativeTo(contentId: String, delta: Int): LiveChannelRef? {
        val current = snapshot
        return current.relativeTo(contentId, delta)
    }

    private data class PlaylistSnapshot(
        val version: Long,
        val channels: List<LiveChannelRef>,
    ) {
        fun relativeTo(contentId: String, delta: Int): LiveChannelRef? {
            if (channels.isEmpty()) return null
            val index = channels.indexOfFirst { it.id == contentId }
            if (index < 0) return null
            val target = ((index + delta) % channels.size + channels.size) % channels.size
            return channels.getOrNull(target)
        }
    }
}

/** Immutable display identity. Opaque ids and display fields are never rendered by string form. */
class LiveChannelPresentation private constructor(
    val contentId: ProviderSelectionId,
    val title: String,
    val logo: String?,
    val playlistVersion: Long,
) {
    override fun toString(): String =
        "LiveChannelPresentation(hasLogo=${logo != null}, playlistVersion=$playlistVersion)"

    companion object {
        private const val FALLBACK_TITLE = "Live TV"
        private const val MAX_TITLE_LENGTH = 256
        private const val MAX_LOGO_LENGTH = 2_048
        private val whitespace = Regex("\\s+")
        private val secretMarkers = listOf(
            "authorization:",
            "bearer ",
            "username=",
            "password=",
            "token=",
            "auth=",
            "cookie:",
        )

        internal fun from(ref: LiveChannelRef, playlistVersion: Long): LiveChannelPresentation? {
            if (ref.id.isBlank()) return null
            return LiveChannelPresentation(
                contentId = ProviderSelectionId(ref.id),
                title = sanitizeTitle(ref.name),
                logo = sanitizeLogo(ref.logo),
                playlistVersion = playlistVersion,
            )
        }

        private fun sanitizeTitle(value: String): String {
            val normalized = clean(value, MAX_TITLE_LENGTH)?.replace(whitespace, " ")
            return normalized
                ?.takeUnless { candidate ->
                    val lowercase = candidate.lowercase()
                    "://" in lowercase || secretMarkers.any(lowercase::contains)
                }
                ?: FALLBACK_TITLE
        }

        private fun sanitizeLogo(value: String?): String? {
            val normalized = clean(value, MAX_LOGO_LENGTH) ?: return null
            val lowercase = normalized.lowercase()
            return normalized.takeUnless { secretMarkers.any(lowercase::contains) }
        }

        private fun clean(value: String?, maximumLength: Int): String? = value
            ?.filterNot { it.code < 0x20 || it.code == 0x7f }
            ?.trim()
            ?.take(maximumLength)
            ?.takeIf(String::isNotEmpty)
    }
}

private fun LiveChannelRef.toPresentation(version: Long): LiveChannelPresentation? =
    LiveChannelPresentation.from(this, version)
