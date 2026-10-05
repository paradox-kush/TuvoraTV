package com.nuvio.tv.playback.host

import com.nuvio.tv.core.picture.AspectMode
import com.nuvio.tv.core.picture.SurfaceZoomTransform
import com.nuvio.tv.core.picture.VideoZoom
import com.nuvio.tv.core.picture.VideoZoomPolicy
import com.nuvio.tv.core.picture.resolveAspectScale
import com.nuvio.tv.playback.core.LiveVideoFitPolicy

/**
 * F28 (TV live aspect/zoom): the live surface's view transform for the regular player's picture
 * model — an [AspectMode] and lane F's manual [VideoZoom] — layered on the live fit.
 *
 * The base is a picture already fitted to the box: Media3 and mpv-embed surfaces get the
 * [LiveVideoFitPolicy] scale (MediaCodec stretches frames to the surface); mpv's GPU output
 * letterboxes inside its own full-box surface, so its base is 1:1. On that fitted picture the mode
 * and zoom apply exactly as the VOD player applies them to its video view ([resolveAspectScale] then
 * [VideoZoomPolicy.surfaceTransform]) — one mechanism for all three live surface kinds, no engine
 * call, no surface relayout on a zap. Pure, so it tests without a device.
 */
internal object LivePictureGeometry {

    /** Null = not known yet (no box, or a decoder surface before the picture size): keep the last shape. */
    fun transform(
        gpuRendered: Boolean,
        videoAspect: Float?,
        boxWidth: Int,
        boxHeight: Int,
        mode: AspectMode,
        zoom: VideoZoom,
    ): SurfaceZoomTransform? {
        if (boxWidth <= 0 || boxHeight <= 0) return null
        val fit = if (gpuRendered) {
            LiveVideoFitPolicy.Scale(1f, 1f)
        } else {
            LiveVideoFitPolicy.fitScale(videoAspect, boxWidth, boxHeight) ?: return null
        }
        val aspect = resolveAspectScale(mode, boxWidth.toFloat() / boxHeight, videoAspect)
        return VideoZoomPolicy.surfaceTransform(
            zoom = zoom,
            widthPx = boxWidth,
            heightPx = boxHeight,
            baseScaleX = fit.x * aspect.scaleX,
            baseScaleY = fit.y * aspect.scaleY,
        )
    }
}
