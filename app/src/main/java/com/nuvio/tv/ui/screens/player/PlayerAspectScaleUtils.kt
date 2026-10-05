package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.picture.AspectMode
import com.nuvio.tv.core.picture.readViewAspectRatio
import com.nuvio.tv.core.picture.resolveAspectScale
import com.nuvio.tv.core.picture.VideoZoom
import com.nuvio.tv.core.picture.VideoZoomPolicy
import android.graphics.Rect
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R

internal fun readExoVideoAspectRatio(playerView: PlayerView): Float? {
    val videoSize = playerView.player?.videoSize
    return if ((videoSize?.height ?: 0) > 0) {
        ((videoSize?.width ?: 0).toFloat() * (videoSize?.pixelWidthHeightRatio ?: 1f)) /
            videoSize!!.height.toFloat()
    } else {
        null
    }
}

internal fun applyExoAspectMode(playerView: PlayerView, mode: AspectMode, zoom: VideoZoom = VideoZoom.IDENTITY) {
    val contentFrame = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
    val surfaceView = resolveVideoSurfaceView(playerView)
    val targetView = contentFrame ?: surfaceView ?: playerView
    val viewAspect = readViewAspectRatio(playerView.width, playerView.height)
    val videoAspect = readExoVideoAspectRatio(playerView)

    resetAspectTransform(playerView)
    contentFrame?.let(::resetAspectTransform)
    surfaceView?.let(::resetAspectTransform)

    applyAspectScale(targetView, mode, viewAspect, videoAspect, zoom)
    centerTargetInPlayer(playerView, targetView)
    // F36: the pan is added after centring, as a fraction of the on-screen (already scaled) picture.
    val z = VideoZoomPolicy.normalize(zoom)
    targetView.translationX += z.panX * targetView.width * targetView.scaleX
    targetView.translationY += z.panY * targetView.height * targetView.scaleY
}

internal fun applyAspectMode(playerView: PlayerView, mode: AspectMode) {
    val targetView = resolveVideoSurfaceView(playerView) ?: playerView
    val viewAspect = readViewAspectRatio(playerView.width, playerView.height)
    val videoAspect = readExoVideoAspectRatio(playerView)
    resetAspectTransform(playerView)

    applyAspectScale(targetView, mode, viewAspect, videoAspect)
}

internal fun addExoAspectLayoutChangeListener(
    playerView: PlayerView,
    listener: View.OnLayoutChangeListener
): () -> Unit {
    val targets = linkedSetOf<View>()
    targets.add(playerView)
    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)?.let(targets::add)
    resolveVideoSurfaceView(playerView)?.let(targets::add)
    targets.forEach { it.addOnLayoutChangeListener(listener) }
    return {
        targets.forEach { it.removeOnLayoutChangeListener(listener) }
    }
}

private fun applyAspectScale(
    targetView: View,
    mode: AspectMode,
    viewAspect: Float,
    videoAspect: Float?,
    zoom: VideoZoom = VideoZoom.IDENTITY,
) {
    val scale = resolveAspectScale(
        mode = mode,
        viewAspect = viewAspect,
        videoAspect = videoAspect
    )
    // F36: the manual zoom multiplies the aspect mode's own scale (VideoZoomPolicy).
    val zoomed = VideoZoomPolicy.surfaceTransform(zoom, targetView.width, targetView.height, scale.scaleX, scale.scaleY)
    targetView.scaleX = zoomed.scaleX
    targetView.scaleY = zoomed.scaleY
}

private fun resetAspectTransform(view: View) {
    view.scaleX = 1.0f
    view.scaleY = 1.0f
    view.translationX = 0.0f
    view.translationY = 0.0f
    if (view.width > 0) {
        view.pivotX = view.width / 2.0f
    }
    if (view.height > 0) {
        view.pivotY = view.height / 2.0f
    }
}

private fun centerTargetInPlayer(playerView: PlayerView, targetView: View) {
    if (
        targetView === playerView ||
        playerView.width <= 0 ||
        playerView.height <= 0 ||
        targetView.width <= 0 ||
        targetView.height <= 0
    ) {
        return
    }

    val targetRect = Rect(0, 0, targetView.width, targetView.height)
    playerView.offsetDescendantRectToMyCoords(targetView, targetRect)
    val playerCenterX = playerView.width / 2.0f
    val playerCenterY = playerView.height / 2.0f
    val targetCenterX = targetRect.left + targetRect.width() / 2.0f
    val targetCenterY = targetRect.top + targetRect.height() / 2.0f
    targetView.translationX = playerCenterX - targetCenterX
    targetView.translationY = playerCenterY - targetCenterY
}

private fun resolveVideoSurfaceView(playerView: PlayerView): View? {
    return findVideoSurfaceView(playerView)
}

private fun findVideoSurfaceView(view: View): View? {
    return when (view) {
        is SurfaceView, is TextureView -> view
        is ViewGroup -> {
            for (index in 0 until view.childCount) {
                val child = findVideoSurfaceView(view.getChildAt(index))
                if (child != null) {
                    return child
                }
            }
            null
        }

        else -> null
    }
}
