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
