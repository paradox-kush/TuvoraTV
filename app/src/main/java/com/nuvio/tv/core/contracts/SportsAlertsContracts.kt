package com.nuvio.tv.core.contracts

import android.view.KeyEvent
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.flow.SharedFlow

/**
 * Neutral seam between upstream-aligned code (MainActivity, the NavHost, Settings, the clean live player) and the
 * fork's in-app sports alerts. The fork side implements and binds these in `core/di/SportsAlertsModule`; nothing
 * upstream-aligned names a radar type.
 */

/** What a banner does when the viewer holds OK. */
sealed interface AlertAction {
    data class OpenGame(val gameId: String) : AlertAction
    data class Watch(val gameId: String, val contentId: String) : AlertAction
    data object OpenSports : AlertAction
}

/** The activity-level surface: key dispatch, route context, the one-shot actions. */
interface SportsAlertsHost {
    /** True = swallow the key (a held-back OK down, or the rest of a held OK). */
    fun onKeyEvent(event: KeyEvent): Boolean

    /** Re-delivers a key to the app exactly as the activity would have (`super.dispatchKeyEvent`). */
    var replaySink: ((KeyEvent) -> Boolean)?

    /** The current navigation destination: route PATTERN plus the entry's own args. */
    fun onRoute(route: String?, contentType: String?, contentId: String?, gameId: String?)

    fun onActivityPaused()

    /** One-shot: the NavHost collects it and opens the destination. */
    val actions: SharedFlow<AlertAction>

    /** Publishes the matched lineup so a Watch action opens with channel up/down working. */
    fun publishLineup(gameId: String): Boolean
}

/** The channels being played now; each player clears only what it set. */
interface SportsAlertsPlayingChannels {
    fun set(id: String)
    fun clear(id: String)

    /** Nothing listens (tests, builds without alerts). */
    object None : SportsAlertsPlayingChannels {
        override fun set(id: String) = Unit
        override fun clear(id: String) = Unit
    }
}

/** The alerts' composables, supplied by the fork side. */
interface SportsAlertsUi {
    /** The "Sports alerts" settings page body (also the Settings category). */
    @Composable
    fun SettingsContent(initialFocusRequester: FocusRequester?)

    /** The banner, drawn inside the activity's root Box: top-right, not focusable. */
    @Composable
    fun BannerHost(box: BoxScope)
}
