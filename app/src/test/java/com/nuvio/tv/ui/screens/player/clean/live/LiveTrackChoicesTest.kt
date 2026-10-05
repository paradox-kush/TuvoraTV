package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.playback.core.PlaybackTrackCatalog
import com.nuvio.tv.playback.core.PlaybackTrackDescriptor
import com.nuvio.tv.playback.core.PlaybackTrackId
import com.nuvio.tv.playback.core.PlaybackTrackType
import com.nuvio.tv.ui.screens.player.clean.live.LiveTrackChoices.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F28: live gets subtitle and audio pickers (asked for in S20; multi-language channels). Today the
 * TV live overlays offer neither, though both engines report the tracks to the clean session.
 */
class LiveTrackChoicesTest {

    private fun audio(id: String, label: String? = null, language: String? = null, channels: Int? = null) =
        PlaybackTrackDescriptor(PlaybackTrackId(id), PlaybackTrackType.AUDIO, label = label, language = language, channelCount = channels)

    private fun sub(id: String, label: String? = null, language: String? = null, forced: Boolean = false) =
        PlaybackTrackDescriptor(PlaybackTrackId(id), PlaybackTrackType.SUBTITLE, label = label, language = language, forced = forced)

    @Test
    fun `audio rows mark the playing track and name the layout`() {
        val catalog = PlaybackTrackCatalog(
            audio = listOf(audio("a1", language = "en", channels = 6), audio("a2", label = "Commentary", channels = 2)),
            selectedAudioTrackId = PlaybackTrackId("a2"),
        )
        assertEquals(
            "rows",
            listOf(
                Choice(PlaybackTrackId("a1"), "English · 5.1", selected = false),
                Choice(PlaybackTrackId("a2"), "Commentary · Stereo", selected = true),
            ),
            LiveTrackChoices.audio(catalog),
        )
    }

    @Test
    fun `subtitles start with Off, which is selected while none is shown`() {
        val catalog = PlaybackTrackCatalog(subtitles = listOf(sub("s1", language = "fr"), sub("s2", forced = true)))
        assertEquals(
            "rows",
            listOf(
                Choice(null, "Off", selected = true),
                Choice(PlaybackTrackId("s1"), "French", selected = false),
                Choice(PlaybackTrackId("s2"), "Track 2 · Forced", selected = false),
            ),
            LiveTrackChoices.subtitles(catalog, offLabel = "Off"),
        )
    }

    @Test
    fun `the shown subtitle is selected, not Off`() {
        val catalog = PlaybackTrackCatalog(
            subtitles = listOf(sub("s1", label = "English CC")),
            selectedSubtitleTrackId = PlaybackTrackId("s1"),
            subtitlesEnabled = true,
        )
        val rows = LiveTrackChoices.subtitles(catalog, offLabel = "Off")
        assertEquals("off not selected", false, rows[0].selected)
        assertEquals("track selected", true, rows[1].selected)
    }

    /** P3 (W2 device pass): embedded CEA-608/708 captions read "Closed captions", not their codec. */
    @Test
    fun `closed caption tracks read Closed captions`() {
        val mpv = PlaybackTrackDescriptor(PlaybackTrackId("c1"), PlaybackTrackType.SUBTITLE, language = "eia-608", codec = "eia_608")
        val exo = PlaybackTrackDescriptor(PlaybackTrackId("c2"), PlaybackTrackType.SUBTITLE, label = "Unknown (application/cea-608)", mimeType = "application/cea-608")
        val spanish = PlaybackTrackDescriptor(PlaybackTrackId("c3"), PlaybackTrackType.SUBTITLE, language = "es", codec = "eia_608")
        val rows = LiveTrackChoices.subtitles(PlaybackTrackCatalog(subtitles = listOf(mpv, exo, spanish, sub("s1", language = "fr"))), offLabel = "Off")
        assertEquals(
            "labels",
            listOf("Off", "Closed captions", "Closed captions", "Spanish · Closed captions", "French"),
            rows.map { it.label },
        )
    }

    /**
     * T6 (W2 device pass): live plays on mpv's direct output, which has no subtitle layer; the HLS
     * subtitle track was listed, picked and never shown. The tracks stay listed but are not pickable.
     */
    @Test
    fun `a direct-output picture lists subtitles but does not offer them`() {
        val catalog = PlaybackTrackCatalog(
            subtitles = listOf(sub("s1", language = "en")),
            selectedSubtitleTrackId = PlaybackTrackId("s1"),
            subtitlesEnabled = true,
        )
        val direct = com.nuvio.tv.playback.core.PlaybackGraph(
            id = "mpv-direct", engine = com.nuvio.tv.playback.core.EngineType.LIBMPV,
            outputProfile = com.nuvio.tv.playback.core.GraphOutputProfile.MPV_DIRECT,
            decoderMode = com.nuvio.tv.playback.core.DecoderMode.HARDWARE,
            audioMode = com.nuvio.tv.playback.core.AudioMode.DECODE,
            surfaceMode = com.nuvio.tv.playback.core.SurfaceMode.NATIVE_EMBED,
        )
        assertEquals("direct output cannot draw", false, LiveTrackChoices.canDrawSubtitles(direct))
        assertEquals("gpu render can", true, LiveTrackChoices.canDrawSubtitles(direct.copy(outputProfile = com.nuvio.tv.playback.core.GraphOutputProfile.MPV_RENDER)))
        assertEquals(
            "the clean Media3 live path has no cue renderer yet",
            false,
            LiveTrackChoices.canDrawSubtitles(direct.copy(engine = com.nuvio.tv.playback.core.EngineType.MEDIA3, outputProfile = com.nuvio.tv.playback.core.GraphOutputProfile.MEDIA3_STANDARD)),
        )
        assertEquals("no graph yet -> not offered", false, LiveTrackChoices.canDrawSubtitles(null))

        val rows = LiveTrackChoices.subtitles(catalog, offLabel = "Off", drawable = false)
        assertEquals("Off stays pickable and selected", Choice(null, "Off", selected = true), rows[0])
        assertEquals("the track is listed, not pickable", Choice(PlaybackTrackId("s1"), "English", selected = false, enabled = false), rows[1])
    }

    @Test
    fun `an unknown language code is shown as given rather than blank`() {
        assertEquals("code", "qaa", LiveTrackChoices.label(audio("x", language = "qaa"), 0))
    }

    @Test
    fun `stream info names engine, picture and tracks from the session facts`() {
        val snapshot = com.nuvio.tv.playback.core.PlaybackSnapshot(
            videoOutputFacts = com.nuvio.tv.playback.core.VideoOutputFacts(dimensions = com.nuvio.tv.playback.core.VideoDimensions(1920, 1080), frameRate = 25f),
            trackCatalog = PlaybackTrackCatalog(audio = listOf(audio("a1")), selectedAudioTrackId = PlaybackTrackId("a1")),
        )
        val lines = LiveTrackChoices.streamInfo(snapshot).toMap()
        assertEquals("video", "1920×1080", lines["Video"])
        assertEquals("frame rate", "25.00 fps", lines["Frame rate"])
        assertEquals("audio tracks", "1", lines["Audio tracks"])
        assertEquals("subtitle tracks", "0", lines["Subtitle tracks"])
    }
}
