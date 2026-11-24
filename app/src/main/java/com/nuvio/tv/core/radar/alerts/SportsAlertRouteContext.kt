package com.nuvio.tv.core.radar.alerts

/** What the current navigation route says about playback and the open game. Pure; fed to the alert policy. */
data class SportsAlertRouteContext(
    val playback: PlaybackContext = PlaybackContext.None,
    /** The `contentId` of the channel/title now playing, when the route carries it (Screen.Player only). */
    val playingContentId: String? = null,
    /** The game whose page is open (Screen.GameDetail). */
    val openGameEventId: String? = null,
) {
    companion object {
        /**
         * [route] is the NavDestination route PATTERN ("player/{streamUrl}/...", "clean-live/{launchToken}",
         * "multiview?start={start}", "game_detail/{gameId}?watching={watching}"). The args are the entry's own.
         */
        fun from(route: String?, contentType: String?, contentId: String?, gameId: String?): SportsAlertRouteContext = when {
            route == null -> SportsAlertRouteContext()
            route.startsWith("player/") -> SportsAlertRouteContext(
                // The legacy player also carries live channels and catch-up (contentType "live").
                playback = if (contentType.equals("live", ignoreCase = true)) PlaybackContext.LiveTv else PlaybackContext.Vod,
                playingContentId = contentId?.takeIf { it.isNotBlank() },
            )
            route.startsWith("clean-live/") -> SportsAlertRouteContext(playback = PlaybackContext.LiveTv)
            // Multiview is TV-only and always live channels.
            route.startsWith("multiview") -> SportsAlertRouteContext(playback = PlaybackContext.LiveTv)
            route.startsWith("game_detail/") -> SportsAlertRouteContext(openGameEventId = gameId?.takeIf { it.isNotBlank() })
            else -> SportsAlertRouteContext()
        }
    }
}

