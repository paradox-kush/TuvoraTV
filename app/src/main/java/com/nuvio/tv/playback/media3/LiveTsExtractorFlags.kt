package com.nuvio.tv.playback.media3

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * TS payload-reader flags for a confirmed raw MPEG-TS live source.
 *
 * Deliberately WITHOUT [DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS] (stock Media3, TiviMate and
 * NuvioPlusIPTV all leave it off). With it on, the H.264 reader also starts a new sample on slice headers, on
 * top of the stream's access unit delimiters, so one picture can reach the decoder as fragments with repeated
 * timestamps. MediaTek decoders then show a few frames and freeze while audio continues (Google TV Streamer
 * `c2.mtk.avc`, OwnTV#284 fixed by dropping this flag; ExoPlayer#2469 on `OMX.MTK`).
 */
@OptIn(UnstableApi::class)
internal object LiveTsExtractorFlags {
    fun rawTransportStream(): Int =
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
}
