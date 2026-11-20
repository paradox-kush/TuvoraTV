package com.nuvio.tv.playback.media3

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.nuvio.tv.playback.core.DecodeSignal
import com.nuvio.tv.playback.core.StreamHdrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class Media3DecodeHealthTest {
    @Test
    fun `an unexpected discontinuity is the audio timestamp signal and other sink errors are not`() {
        assertEquals(
            DecodeSignal.AudioSinkDiscontinuity(3),
            media3AudioSinkSignal(AudioSink.UnexpectedDiscontinuityException(89_633_000_000L, 120_000L), 3),
        )
        assertEquals(
            DecodeSignal.AudioSinkError(1),
            media3AudioSinkSignal(IllegalStateException("write failed"), 1),
        )
    }

    @Test
    fun `audio format facts carry codec channels and rate and drop unset values`() {
        val facts = media3AudioFormatFacts(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).setSampleRate(48_000).build(),
        )
        assertEquals(MimeTypes.AUDIO_E_AC3, facts.audioCodec)
        assertEquals(6, facts.audioChannels)
        assertEquals(48_000, facts.audioSampleRate)

        val unset = media3AudioFormatFacts(Format.Builder().build())
        assertNull(unset.audioChannels)
        assertNull(unset.audioSampleRate)
    }

    @Test
    fun `reorder depth is the format value when valid and null when unset`() {
        assertEquals(2, media3ReorderDepth(Format.Builder().setMaxNumReorderSamples(2).build()))
        assertEquals(0, media3ReorderDepth(Format.Builder().setMaxNumReorderSamples(0).build()))
        assertNull(media3ReorderDepth(Format.Builder().build()))
        assertNull(media3ReorderDepth(Format.Builder().setMaxNumReorderSamples(40).build()))
    }

    @Test
    fun `hdr comes from the colour transfer and never guesses sdr`() {
        fun hdr(transfer: Int?) = media3Hdr(
            Format.Builder().apply {
                if (transfer != null) setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build())
            }.build(),
        )
        assertEquals(StreamHdrCode.HDR10, hdr(C.COLOR_TRANSFER_ST2084))
        assertEquals(StreamHdrCode.HLG, hdr(C.COLOR_TRANSFER_HLG))
        assertEquals(StreamHdrCode.SDR, hdr(C.COLOR_TRANSFER_SDR))
        assertNull("no colour info says nothing", hdr(null))
    }
}
