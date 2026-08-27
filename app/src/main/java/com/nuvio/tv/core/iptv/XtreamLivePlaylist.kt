package com.nuvio.tv.core.iptv

import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.ProviderSelectionId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ordered, profile-bound channel identities currently being watched, so a player can select
 * the next/previous channel (TiViMate-style) without retaining provider transport. The guide or
 * Sports publishes it before launching a channel; playback resolves fresh media after selection.
 *
 * ponytail: a single process-lifetime list — only one live session is active at a time.
 */
@Singleton
class XtreamLivePlaylist @Inject constructor() {
    @Volatile
    private var snapshot: PlaylistSnapshot? = null

    @Synchronized
    fun set(
        profileId: PlaybackProfileId,
        channels: List<XtreamLiveChannelIdentity>,
    ) {
        require(profileId.isPositiveNumeric()) { "Live playlist profile id must be positive" }
        val currentVersion = snapshot?.version ?: 0
        check(currentVersion < Long.MAX_VALUE) { "Live playlist version exhausted" }
        snapshot = PlaylistSnapshot(
            version = currentVersion + 1,
            profileId = profileId,
            channels = channels.toList(),
        )
    }

    /** Display-only lookup for the exact current immutable playlist snapshot. */
    fun presentationFor(
        profileId: PlaybackProfileId,
        contentId: String,
    ): LiveChannelPresentation? {
        val current = snapshot?.takeFor(profileId) ?: return null
        return current.channels.firstOrNull { it.contentId.value == contentId }
            ?.toPresentation(current.version)
    }

    /** Display-only neighbor from one immutable snapshot; no transport value crosses the result. */
    fun relativePresentation(
        profileId: PlaybackProfileId,
        contentId: String,
        delta: Int,
    ): LiveChannelPresentation? {
        val current = snapshot?.takeFor(profileId) ?: return null
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
    fun relativeTo(
        profileId: PlaybackProfileId,
        contentId: String,
        delta: Int,
    ): XtreamLiveChannelIdentity? {
        val current = snapshot?.takeFor(profileId) ?: return null
        return current.relativeTo(contentId, delta)
    }

    private data class PlaylistSnapshot(
        val version: Long,
        val profileId: PlaybackProfileId,
        val channels: List<XtreamLiveChannelIdentity>,
    ) {
        fun relativeTo(contentId: String, delta: Int): XtreamLiveChannelIdentity? {
            if (channels.isEmpty()) return null
            val index = channels.indexOfFirst { it.contentId.value == contentId }
            if (index < 0) return null
            val target = ((index + delta) % channels.size + channels.size) % channels.size
            return channels.getOrNull(target)
        }
    }

    private fun PlaylistSnapshot.takeFor(requestedProfile: PlaybackProfileId): PlaylistSnapshot? =
        takeIf {
            requestedProfile.isPositiveNumeric() &&
                profileId == requestedProfile &&
                profileId.isPositiveNumeric()
        }
}

/** Immutable, URL-free channel identity retained by the process-local playlist snapshot. */
class XtreamLiveChannelIdentity private constructor(
    val contentId: ProviderSelectionId,
    val title: String,
    val logo: String?,
) {
    override fun toString(): String =
        "XtreamLiveChannelIdentity(hasLogo=${logo != null})"

    companion object {
        fun from(
            contentId: String,
            title: String,
            logo: String?,
        ): XtreamLiveChannelIdentity? = contentId
            .takeIf(String::isNotBlank)
            ?.let { XtreamLiveChannelIdentity(ProviderSelectionId(it), title, logo) }
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

        internal fun from(
            identity: XtreamLiveChannelIdentity,
            playlistVersion: Long,
        ): LiveChannelPresentation {
            return LiveChannelPresentation(
                contentId = identity.contentId,
                title = sanitizeTitle(identity.title),
                logo = sanitizeLogo(identity.logo),
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

private fun XtreamLiveChannelIdentity.toPresentation(version: Long): LiveChannelPresentation =
    LiveChannelPresentation.from(this, version)

private fun PlaybackProfileId.isPositiveNumeric(): Boolean =
    value.toIntOrNull()?.let { it > 0 } == true
