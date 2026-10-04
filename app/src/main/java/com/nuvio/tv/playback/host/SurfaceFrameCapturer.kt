package com.nuvio.tv.playback.host

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View

/** Copies the picture currently on a playback surface into [into]; [done] reports success. */
fun interface SurfaceFrameCapturer {
    fun capture(view: View, into: Bitmap, done: (Boolean) -> Unit)
}

/**
 * SurfaceView: PixelCopy reads the surface's latest buffer (API 24+), scaled into the bitmap, so it
 * works for MediaCodec and mpv mediacodec_embed output whatever the compositor does with the layer.
 * Secure surfaces cannot be copied (the copy fails and the previous frame is kept).
 * TextureView: its own GPU texture.
 */
object PixelCopyFrameCapturer : SurfaceFrameCapturer {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun capture(view: View, into: Bitmap, done: (Boolean) -> Unit) {
        when (view) {
            is SurfaceView -> {
                if (!view.holder.surface.isValid) return done(false)
                runCatching {
                    PixelCopy.request(view, into, { result -> done(result == PixelCopy.SUCCESS) }, mainHandler)
                }.onFailure { done(false) }
            }
            is TextureView -> done(view.isAvailable && runCatching { view.getBitmap(into) }.isSuccess)
            else -> done(false)
        }
    }
}
