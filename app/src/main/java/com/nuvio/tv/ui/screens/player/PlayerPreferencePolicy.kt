package com.nuvio.tv.ui.screens.player

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Player preferences (community plan G7: F37 remember, F36 manual zoom, F47/UX61 subtitle style).
 *
 * Hand-port of NuvioMobile's commonMain `PlayerPreferencePolicy.kt` — same names, same numbers, so the
 * five apps agree on what a zoom step, a remembered aspect or a "dim" subtitle box means. Pure: no
 * Android, no player, no storage. Persistence is TrackPreferenceDataStore (per series) and
 * DeviceLocalPlayerPreferences / PlayerSettingsDataStore (global).
 */

/**
 * Manual zoom (F36) layered on top of [AspectMode]. [scaleX]/[scaleY] multiply the picture the aspect
 * mode produced; [panX]/[panY] move it by a fraction of the scaled picture (mpv's `video-pan` unit).
 */
data class VideoZoom(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val isIdentity: Boolean
        get() = VideoZoomPolicy.normalize(this) == IDENTITY

    companion object {
        val IDENTITY = VideoZoom()
    }
}

enum class VideoZoomAxis { Both, Width, Height, PanX, PanY }

data class SurfaceZoomTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
)

object VideoZoomPolicy {
    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 3f
    const val SCALE_STEP = 0.05f
    const val MAX_PAN = 0.5f
    const val PAN_STEP = 0.02f

    fun normalize(zoom: VideoZoom): VideoZoom = VideoZoom(
        scaleX = round2(zoom.scaleX.finiteOr(1f).coerceIn(MIN_SCALE, MAX_SCALE)),
        scaleY = round2(zoom.scaleY.finiteOr(1f).coerceIn(MIN_SCALE, MAX_SCALE)),
        panX = round2(zoom.panX.finiteOr(0f).coerceIn(-MAX_PAN, MAX_PAN)),
        panY = round2(zoom.panY.finiteOr(0f).coerceIn(-MAX_PAN, MAX_PAN)),
    )

    fun adjust(zoom: VideoZoom, axis: VideoZoomAxis, steps: Int): VideoZoom {
        val scaleDelta = SCALE_STEP * steps
        val panDelta = PAN_STEP * steps
        val next = when (axis) {
            VideoZoomAxis.Both -> zoom.copy(scaleX = zoom.scaleX + scaleDelta, scaleY = zoom.scaleY + scaleDelta)
            VideoZoomAxis.Width -> zoom.copy(scaleX = zoom.scaleX + scaleDelta)
            VideoZoomAxis.Height -> zoom.copy(scaleY = zoom.scaleY + scaleDelta)
            VideoZoomAxis.PanX -> zoom.copy(panX = zoom.panX + panDelta)
            VideoZoomAxis.PanY -> zoom.copy(panY = zoom.panY + panDelta)
        }
        return normalize(next)
    }

    /** libmpv properties (the clean live mpv path and any future mpv-property consumer on TV). */
    fun mpvProperties(zoom: VideoZoom): List<Pair<String, String>> {
        val z = normalize(zoom)
        return listOf(
            "video-scale-x" to z.scaleX.mpvNumber(),
            "video-scale-y" to z.scaleY.mpvNumber(),
            "video-pan-x" to z.panX.mpvNumber(),
            "video-pan-y" to z.panY.mpvNumber(),
        )
    }

    /**
     * The View transform for TV's legacy player, which scales the video view for BOTH engines
     * (ExoPlayer's content frame and NuvioMpvSurfaceView). [baseScaleX]/[baseScaleY] are the aspect
     * mode's own scale ([resolveAspectScale]); the zoom multiplies it and the pan is a fraction of the
     * resulting on-screen picture.
     */
    fun surfaceTransform(
        zoom: VideoZoom,
        widthPx: Int,
        heightPx: Int,
        baseScaleX: Float = 1f,
        baseScaleY: Float = 1f,
    ): SurfaceZoomTransform {
        val z = normalize(zoom)
        val sx = baseScaleX * z.scaleX
        val sy = baseScaleY * z.scaleY
        return SurfaceZoomTransform(
            scaleX = sx,
            scaleY = sy,
            translationX = z.panX * widthPx.coerceAtLeast(0) * sx,
            translationY = z.panY * heightPx.coerceAtLeast(0) * sy,
        )
    }

    fun scaleLabel(zoom: VideoZoom): String {
        val z = normalize(zoom)
        val w = (z.scaleX * 100).roundToInt()
        val h = (z.scaleY * 100).roundToInt()
        return if (w == h) "$w%" else "$w% × $h%"
    }

    private fun Float.finiteOr(fallback: Float): Float = if (isNaN() || isInfinite()) fallback else this
    private fun round2(value: Float): Float = (value * 100f).roundToInt() / 100f

    private fun Float.mpvNumber(): String {
        val hundredths = (this * 100f).roundToInt()
        val sign = if (hundredths < 0) "-" else ""
        val magnitude = abs(hundredths)
        return "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}"
    }
}

/** The per-series picture memory (TrackPreferenceDataStore): aspect mode name + manual zoom. */
data class PictureMemory(
    val aspectMode: String? = null,
    val zoom: VideoZoom? = null,
)

data class PictureChoice(
    val aspectMode: AspectMode,
    val zoom: VideoZoom,
)

/**
 * "Remember my player preferences" (F37). Scope: per series (the player's contentId — a Stremio
 * series id or an Xtream series/VOD id), next to the per-series track memory that already existed.
 * Fallback: the global settings — preferred languages for tracks, the device's last-used aspect
 * mode, and no zoom. The toggle governs only the per-series memory; the device-wide last-used aspect
 * predates it and keeps working either way.
 */
object PlayerPreferencePolicy {
    const val DEFAULT_REMEMBER = true

    fun <T> seriesMemory(rememberEnabled: Boolean, stored: T?): T? = if (rememberEnabled) stored else null

    fun persistsSeriesChoice(rememberEnabled: Boolean, seriesKey: String?): Boolean =
        rememberEnabled && !seriesKey.isNullOrBlank()

    fun initialPicture(
        rememberEnabled: Boolean,
        stored: PictureMemory?,
        globalAspectMode: AspectMode,
    ): PictureChoice {
        val memory = seriesMemory(rememberEnabled, stored)
        val mode = memory?.aspectMode
            ?.let { name -> AspectMode.entries.firstOrNull { it.name == name } }
            ?: globalAspectMode
        val zoom = memory?.zoom?.let(VideoZoomPolicy::normalize) ?: VideoZoom.IDENTITY
        return PictureChoice(aspectMode = mode, zoom = zoom)
    }

    /** Identity zoom is stored as "no zoom" so an untouched series stays clean. */
    fun pictureMemory(aspectMode: AspectMode, zoom: VideoZoom): PictureMemory {
        val z = VideoZoomPolicy.normalize(zoom)
        return PictureMemory(aspectMode = aspectMode.name, zoom = z.takeUnless { it == VideoZoom.IDENTITY })
    }
}

/**
 * Subtitle box, outline and side padding for libmpv (F47 + UX61). See the mobile twin for the full
 * rationale: `opaque-box` (libass BorderStyle 3) paints the box in the OUTLINE colour, so the "dim"
 * preset rendered near-solid; `background-box` (BorderStyle 4) paints it in `sub-back-color` with its
 * alpha and pads it by `sub-shadow-offset`. `sub-border-*` names are accepted by every mpv we ship.
 */
object SubtitleStyleMpvMapping {
    const val BOX_PADDING = 4.0
    const val MPV_DEFAULT_MARGIN_X = 19.0
    private const val REFERENCE_WIDTH_16_9 = 1280.0

    fun borderStyle(backgroundAlpha: Float): String =
        if (backgroundAlpha > 0f) "background-box" else "outline-and-shadow"

    fun marginX(sideMarginPercent: Int, referenceWidth: Double = REFERENCE_WIDTH_16_9): Double {
        val percent = sideMarginPercent.coerceIn(0, SubtitleSideMargin.MAX_PERCENT)
        return maxOf(MPV_DEFAULT_MARGIN_X, referenceWidth * percent / 100.0)
    }

    fun properties(
        backgroundColorHex: String,
        backgroundAlpha: Float,
        outlineColorHex: String,
        outlineSize: Double,
        sideMarginPercent: Int,
    ): List<Pair<String, String>> {
        val box = backgroundAlpha > 0f
        return listOf(
            "sub-back-color" to backgroundColorHex,
            "sub-border-color" to outlineColorHex,
            "sub-border-size" to outlineSize.coerceAtLeast(0.0).mpvNumber(),
            "sub-border-style" to borderStyle(backgroundAlpha),
            "sub-shadow-offset" to (if (box) BOX_PADDING else 0.0).mpvNumber(),
            "sub-margin-x" to marginX(sideMarginPercent).mpvNumber(),
        )
    }

    private fun Double.mpvNumber(): String {
        val tenths = Math.round(this * 10.0)
        return "${tenths / 10}.${abs(tenths % 10)}"
    }
}

/** Horizontal subtitle padding (F47): percent of the picture width kept clear on each side. */
object SubtitleSideMargin {
    const val DEFAULT_PERCENT = 5
    const val MAX_PERCENT = 20

    fun paddingPx(widthPx: Int, sideMarginPercent: Int): Int =
        (widthPx.coerceAtLeast(0) * sideMarginPercent.coerceIn(0, MAX_PERCENT) / 100f).roundToInt()
}
