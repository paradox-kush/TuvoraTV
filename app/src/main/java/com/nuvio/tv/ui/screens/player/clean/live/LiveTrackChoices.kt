package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.PlaybackGraph
import com.nuvio.tv.playback.core.PlaybackSnapshot
import com.nuvio.tv.playback.core.PlaybackTrackCatalog
import com.nuvio.tv.playback.core.PlaybackTrackDescriptor
import com.nuvio.tv.playback.core.PlaybackTrackId

/**
 * F28: the live overlay's subtitle/audio pickers and stream-info lines, from the clean session's
 * own snapshot (engine-neutral, URL-free). Pure, so the labels and the "Off" row test without a
 * player.
 */
internal object LiveTrackChoices {

    /** [enabled] false = listed but not pickable here (T6: an output that cannot draw subtitles). */
    data class Choice(val id: PlaybackTrackId?, val label: String, val selected: Boolean, val enabled: Boolean = true)

    /** Audio: one row per track; the selected one marked. */
    fun audio(catalog: PlaybackTrackCatalog): List<Choice> =
        catalog.audio.mapIndexed { index, track ->
            Choice(track.id, label(track, index), track.id == catalog.selectedAudioTrackId)
        }

    /**
     * Subtitles: "Off" first (id = null), then one row per track. [drawable] false (T6): the live
     * picture is on a path with no subtitle layer, so the tracks are listed but not pickable — the
     * picker says why ([canDrawSubtitles]) instead of a pick that silently shows nothing.
     */
    fun subtitles(
        catalog: PlaybackTrackCatalog,
        offLabel: String,
        closedCaptionsLabel: String = CLOSED_CAPTIONS,
        drawable: Boolean = true,
    ): List<Choice> =
        listOf(Choice(null, offLabel, !drawable || !catalog.subtitlesEnabled || catalog.selectedSubtitleTrackId == null)) +
            catalog.subtitles.mapIndexed { index, track ->
                Choice(
                    track.id,
                    subtitleLabel(track, index, closedCaptionsLabel),
                    drawable && catalog.subtitlesEnabled && track.id == catalog.selectedSubtitleTrackId,
                    enabled = drawable,
                )
            }

    /**
     * T6 (W2 device pass): live plays on mpv's DIRECT output (mediacodec_embed — the decoder renders
     * straight to the video plane, product decision 2026-08-28: smooth video first). That path has
     * no layer to draw subtitles on, so an HLS subtitle track was listed, picked, and never shown.
     */
    fun canDrawSubtitles(graph: PlaybackGraph?): Boolean = graph?.outputProfile != GraphOutputProfile.MPV_DIRECT

    /** P3: a closed-caption track reads "Closed captions" (or its real language), never its codec. */
    fun subtitleLabel(track: PlaybackTrackDescriptor, index: Int, closedCaptionsLabel: String = CLOSED_CAPTIONS): String {
        if (!ClosedCaptionTracks.isClosedCaption(track)) return label(track, index)
        val name = ClosedCaptionTracks.realLanguage(track)?.let { "${languageName(it)} · $closedCaptionsLabel" }
            ?: closedCaptionsLabel
        return if (track.forced) "$name · Forced" else name
    }

    private const val CLOSED_CAPTIONS = "Closed captions"

    /** "English · 5.1", falling back to the language, then "Track N". Never blank. */
    fun label(track: PlaybackTrackDescriptor, index: Int): String {
        val name = track.label?.takeIf { it.isNotBlank() }
            ?: track.language?.takeIf { it.isNotBlank() }?.let(::languageName)
            ?: "Track ${index + 1}"
        val channels = when (track.channelCount) {
            null, 0 -> null
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${track.channelCount} ch"
        }
        val forced = if (track.forced) "Forced" else null
        return listOfNotNull(name, channels, forced).joinToString(" · ")
    }

    private fun languageName(code: String): String =
        java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH)
            .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code

    /** Stream info for support: engine, decoder, picture, frame rate, tracks. No URL ever. */
    fun streamInfo(snapshot: PlaybackSnapshot): List<Pair<String, String>> = buildList {
        snapshot.graph?.let { graph ->
            add("Engine" to graph.engine.name.lowercase().replaceFirstChar { it.uppercase() })
            add("Decoder" to graph.decoderMode.name.lowercase().replace('_', ' '))
            add("Output" to graph.surfaceMode.name.lowercase().replace('_', ' '))
        }
        val dims = snapshot.videoOutputFacts.dimensions ?: snapshot.tracks.videoDimensions
        dims?.let { add("Video" to "${it.width}×${it.height}") }
        snapshot.videoOutputFacts.frameRate?.takeIf { it > 0f }?.let { add("Frame rate" to "%.2f fps".format(java.util.Locale.ROOT, it)) }
        val audio = snapshot.trackCatalog.audio.firstOrNull { it.id == snapshot.trackCatalog.selectedAudioTrackId }
        audio?.codec?.takeIf { it.isNotBlank() }?.let { add("Audio" to it) }
        add("Audio tracks" to snapshot.trackCatalog.audio.size.toString())
        add("Subtitle tracks" to snapshot.trackCatalog.subtitles.size.toString())
    }
}
