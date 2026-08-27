package com.nuvio.tv.ui.screens.iptv

import android.os.Build

/**
 * Chooses the video surface used inside the Compose-hosted Live TV guide.
 *
 * The Amazon AFTKM / MediaTek MT8696 hardware composer was device-proven to place a pane-sized
 * Media3 SurfaceView over the guide's entire Compose content layer: audio played and accessibility
 * still exposed the complete guide, but the main region was black. A TextureView participates in
 * the normal View/Compose composition and avoids that vendor surface-layer failure.
 *
 * libmpv's Android renderer is SurfaceView-only. Hardware testing subsequently reproduced the
 * same opaque-layer-over-Compose failure with the mpv guide preview on the onn/Amlogic box. The
 * mpv surface showed its correctly scaled pane video, but its black backing occupied the whole
 * window and hid most guide text. Therefore mpv is never embedded in the guide: Media3 owns the
 * preview, while mpv remains available for fullscreen playback/recovery where a full-window
 * SurfaceView is intentional. This also keeps 4K preview on MediaCodec's direct surface path
 * instead of mpv's AImageReader/GL copy.
 *
 * This is intentionally narrow. SurfaceView remains the preferred path for fullscreen video and
 * every unproven device because it has better power, HDR, timing, and secure-output behavior.
 */
internal object LiveGuideSurfacePolicy {

    data class Device(
        val manufacturer: String,
        val model: String,
        val device: String,
        val hardware: String,
        val board: String,
    )

    data class Decision(
        val useTextureView: Boolean,
        /** libmpv's SurfaceView must not be embedded inside a Compose guide on Android TV. */
        val allowMpvPreview: Boolean,
    )

    fun evaluate(device: Device): Decision {
        fun String.matches(value: String): Boolean = equals(value, ignoreCase = true)
        val isAmazon = device.manufacturer.matches("Amazon")
        val isProvenMt8696Family =
            device.model.matches("AFTKM") ||
                device.device.matches("karat") ||
                device.hardware.contains("mt8696", ignoreCase = true) ||
                device.board.contains("mt8696", ignoreCase = true)
        val affected = isAmazon && isProvenMt8696Family
        return Decision(
            useTextureView = affected,
            allowMpvPreview = false,
        )
    }

    fun currentDevice(): Decision = evaluate(
        Device(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            board = Build.BOARD.orEmpty(),
        )
    )
}
