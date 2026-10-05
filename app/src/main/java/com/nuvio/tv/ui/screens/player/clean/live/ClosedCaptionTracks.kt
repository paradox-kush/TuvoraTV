package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.playback.core.PlaybackTrackDescriptor

/**
 * P3 (W2 device pass, TV twin of NuvioMobile `ClosedCaptionTracks`): embedded CEA-608/708 captions
 * (common on live IPTV) reach the picker named after their codec or MIME type — libmpv "eia_608"
 * (language "eia-608"), ExoPlayer "Unknown (application/cea-608)" — and with no real language, so a
 * viewer saw a codec name or "Track 1". This is the one place that recognises a caption track; the
 * picker calls it "Closed captions". A caption track that declares a real language keeps it.
 */
internal object ClosedCaptionTracks {

    private val MARKERS = listOf(
        "eia_608", "eia-608", "eia608", "eia_708", "eia-708", "eia708",
        "cea_608", "cea-608", "cea608", "cea_708", "cea-708", "cea708",
        "closed caption",
    )

    fun isClosedCaption(vararg hints: String?): Boolean =
        hints.any { hint -> hint != null && MARKERS.any { hint.contains(it, ignoreCase = true) } }

    fun isClosedCaption(track: PlaybackTrackDescriptor): Boolean =
        isClosedCaption(track.label, track.language, track.codec, track.mimeType)

    /** The track's language once a caption-format "language" (libmpv's "eia-608") is set aside. */
    fun realLanguage(track: PlaybackTrackDescriptor): String? =
        track.language?.takeUnless { it.isBlank() || isClosedCaption(it) || it.equals("und", ignoreCase = true) }
}
