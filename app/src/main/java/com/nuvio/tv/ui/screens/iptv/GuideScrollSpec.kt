package com.nuvio.tv.ui.screens.iptv

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/**
 * B56: how the guide's category and channel lists scroll to the focused row.
 *
 * The app-wide focus scroll ([com.nuvio.tv.ui.components.NuvioScrollDefaults.smoothScrollSpec])
 * springs with stiffness 180 — a 180 ms duration token used as a stiffness, about 8x softer than
 * Compose's own bring-into-view spring. On a held D-pad the guide accepts a move every
 * [GuideKeyRepeatPolicy.VERTICAL_GATE_MS]; that spring needs roughly 2.5 gated moves to bring one row
 * mostly into place, so on a large category the scroll trails the focus and keeps running after the
 * key is released (the "dead loop" of B56). The guide keeps the app's distance rule (the same pivot)
 * and scrolls with Compose's default spring instead. Foundation 1.11/1.12 still read
 * `scrollAnimationSpec` (ContentInViewNode → UpdatableAnimationState), deprecated or not.
 *
 * Guide-local on purpose: retuning the app-wide spring changes the feel of every screen, a product
 * call recorded separately.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object GuideScrollSpec {
    /** Compose's own bring-into-view spring (`BringIntoViewSpec.DefaultScrollAnimationSpec`). */
    val settleSpec: AnimationSpec<Float> = spring()

    fun around(base: BringIntoViewSpec): BringIntoViewSpec = object : BringIntoViewSpec {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = settleSpec

        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            base.calculateScrollDistance(offset, size, containerSize)
    }
}
