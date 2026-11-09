package com.nuvio.tv.playback.media3

import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTsExtractorFlagsTest {

    @Test
    fun `live TS never splits H264 pictures on slice headers`() {
        // Slice-header access-unit detection on top of the stream's own delimiters hands MediaTek
        // decoders split pictures with repeated timestamps: picture freezes, audio keeps playing
        // (Google TV Streamer c2.mtk.avc, OwnTV#284; ExoPlayer#2469 on OMX.MTK).
        val flags = LiveTsExtractorFlags.rawTransportStream()
        assertEquals(
            "FLAG_DETECT_ACCESS_UNITS must be off for live TS",
            0,
            flags and DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
    }

    @Test
    fun `live TS keeps non-IDR keyframes and ignores splice info`() {
        val flags = LiveTsExtractorFlags.rawTransportStream()
        assertTrue(flags and DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES != 0)
        assertTrue(flags and DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM != 0)
    }
}
