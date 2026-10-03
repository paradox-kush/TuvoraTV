package com.nuvio.tv.playback.core

/**
 * Shapes decoder-scaled live video to its true display aspect.
 *
 * MediaCodec ignores pixel aspect when drawing onto a Surface (it scales each frame to fill the
 * surface), and mpv's `mediacodec_embed` does no aspect handling at all, so the host must shape the
 * surface itself. The result is a scale applied to a full-box surface view, centred: no relayout,
 * no `surfaceChanged`, no black flash on a zap.
 */
object LiveVideoFitPolicy {
    data class Scale(val x: Float, val y: Float)

    /** Display aspect from coded size and pixel aspect; invalid ratios mean square pixels. */
    fun displayAspect(width: Int, height: Int, pixelWidthHeightRatio: Float): Float? {
        if (width <= 0 || height <= 0) return null
        val ratio = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        return width * ratio / height
    }

    /**
     * Letterbox/pillarbox scale for a picture of [displayAspect] inside a [boxWidth]x[boxHeight]
     * box. Null means "unknown — keep the previous geometry" (e.g. a zap before the size arrives).
     */
    fun fitScale(displayAspect: Float?, boxWidth: Int, boxHeight: Int): Scale? {
        if (displayAspect == null || !displayAspect.isFinite() || displayAspect <= 0f) return null
        if (boxWidth <= 0 || boxHeight <= 0) return null
        val boxAspect = boxWidth.toFloat() / boxHeight
        val deformation = displayAspect / boxAspect - 1f
        if (kotlin.math.abs(deformation) <= MAX_ASPECT_DEFORMATION) return FULL
        return if (displayAspect < boxAspect) {
            Scale(x = displayAspect / boxAspect, y = 1f)
        } else {
            Scale(x = 1f, y = boxAspect / displayAspect)
        }
    }

    private val FULL = Scale(1f, 1f)

    /** Same tolerance as media3's AspectRatioFrameLayout: below 1% the bars are not worth it. */
    private const val MAX_ASPECT_DEFORMATION = 0.01f
}
