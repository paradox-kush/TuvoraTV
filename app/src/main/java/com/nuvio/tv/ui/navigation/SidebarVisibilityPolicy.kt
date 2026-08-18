package com.nuvio.tv.ui.navigation

/**
 * Whether the nav rail belongs on screen.
 *
 * Two independent facts decide it, and both must agree:
 *
 * - the ROUTE has a sidebar at all (Home, Search, Library, IPTV, Sports, Settings — the rootRoutes
 *   set), and
 * - no player currently owns the screen ([com.nuvio.tv.updater.ImmersivePlaybackGate]).
 *
 * The second half is not optional. The IPTV and Sports hubs are rootRoutes on purpose — that is
 * what gives them the rail, LEFT-to-open, and BACK-returns-to-sidebar — but the live guide expands
 * its preview to fullscreen *within* that same route. Route alone therefore cannot tell "browsing
 * the hub" from "watching a channel fullscreen", and the rail ends up painted over the video.
 *
 * Pure so the rule is pinned by tests rather than only being observable on a TV.
 */
object SidebarVisibilityPolicy {

    /**
     * @param routeHasSidebar the current route is one of the sidebar's root destinations.
     * @param immersivePlayback a player has declared it owns the screen.
     */
    fun showSidebar(routeHasSidebar: Boolean, immersivePlayback: Boolean): Boolean =
        routeHasSidebar && !immersivePlayback
}
