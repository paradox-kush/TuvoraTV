package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.contracts.LivePausePhase
import com.nuvio.tv.core.contracts.LivePauseView
import com.nuvio.tv.ui.screens.player.clean.live.LiveControlsPolicy

/**
 * What the guide screen reads of live pause. Positions are left out on purpose: the controller republishes
 * them about twice a second while shifted, and collecting the whole view recomposed the guide at that rate.
 * The scrub key handler reads the full view at key-press time instead.
 */
data class LivePauseGuideProjection(
    val phase: LivePausePhase,
    val paused: Boolean,
    val scrubbable: Boolean,
)

fun LivePauseView.toGuideProjection() =
    LivePauseGuideProjection(phase, paused, LiveControlsPolicy.isScrubbable(phase))
