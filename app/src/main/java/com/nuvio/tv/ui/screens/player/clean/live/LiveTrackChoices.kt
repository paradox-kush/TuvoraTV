package com.nuvio.tv.ui.screens.player.clean.live

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

    data class Choice(val id: PlaybackTrackId?, val label: String, val selected: Boolean)

    /** Audio: one row per track; the selected one marked. */
    fun audio(catalog: PlaybackTrackCatalog): List<Choice> =
        catalog.audio.mapIndexed { index, track ->
            Choice(track.id, label(track, index), track.id == catalog.selectedAudioTrackId)
        }

    /** Subtitles: "Off" first (id = null), then one row per track. */
    fun subtitles(catalog: PlaybackTrackCatalog, offLabel: String): List<Choice> =
        listOf(Choice(null, offLabel, !catalog.subtitlesEnabled || catalog.selectedSubtitleTrackId == null)) +
            catalog.subtitles.mapIndexed { index, track ->
                Choice(track.id, label(track, index), catalog.subtitlesEnabled && track.id == catalog.selectedSubtitleTrackId)
            }

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
