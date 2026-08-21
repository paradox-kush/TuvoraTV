package com.nuvio.tv.ui.screens.iptv

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Renderers for the live-guide preview player. Two departures from the bare default `ExoPlayer`, both
 * targeting the Onn 4K channel-switch stutter/black (root-caused on-device 2026-08-21):
 *  - decoder fallback ON, so an Amlogic codec error (`0x80000000`) drops to a software decoder
 *    instead of a black screen — matching the app's fullscreen `PlayerScreen` and the mobile engine;
 *  - [ResolutionAwareVideoRenderer], which forces a fresh codec on a resolution change so a
 *    4K-sized codec is never reused for a smaller channel.
 */
@OptIn(UnstableApi::class)
internal class LivePreviewRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            ResolutionAwareVideoRenderer(
                context,
                codecAdapterFactory,
                mediaCodecSelector,
                allowedVideoJoiningTimeMs,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                MAX_DROPPED_FRAMES_TO_NOTIFY,
            )
        )
        // Deliberately no extension (software/ffmpeg) VIDEO renderers: the live preview decodes
        // hardware H.264/HEVC and the app bundles no video extension renderer. Decoder fallback (above)
        // still covers the software path when the hardware codec refuses a stream.
    }

    private companion object {
        // Matches DefaultRenderersFactory's own (private) MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY.
        const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50
    }
}

/**
 * A [MediaCodecVideoRenderer] that refuses to reuse the video codec across a resolution change, so
 * each channel gets a codec sized for its own stream instead of decoding a small stream through the
 * oversized codec left over from a 4K channel. See [LiveCodecReusePolicy].
 */
@OptIn(UnstableApi::class)
private class ResolutionAwareVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    allowedJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify,
) {
    override fun canReuseCodec(
        codecInfo: MediaCodecInfo,
        oldFormat: Format,
        newFormat: Format,
        isAdaptiveFormatChange: Boolean,
    ): DecoderReuseEvaluation {
        if (LiveCodecReusePolicy.resolutionChanged(
                oldFormat.width, oldFormat.height, newFormat.width, newFormat.height,
            )
        ) {
            return DecoderReuseEvaluation(
                codecInfo.name,
                oldFormat,
                newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO,
                DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND,
            )
        }
        return super.canReuseCodec(codecInfo, oldFormat, newFormat, isAdaptiveFormatChange)
    }
}
