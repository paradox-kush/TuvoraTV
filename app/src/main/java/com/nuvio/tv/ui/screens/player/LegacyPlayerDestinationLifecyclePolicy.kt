package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.Lifecycle

/** Terminal cleanup for a legacy player destination whose ViewModel may be navigation-saved. */
internal object LegacyPlayerDestinationLifecyclePolicy {
    fun shouldRelease(
        event: Lifecycle.Event,
        activityIsChangingConfigurations: Boolean,
    ): Boolean =
        event == Lifecycle.Event.ON_DESTROY && !activityIsChangingConfigurations
}
