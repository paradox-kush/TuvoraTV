package com.nuvio.tv.updater

/**
 * Decides whether the update banner may take layout space right now.
 *
 * Distinct from [UpdateBannerPolicy], which answers "is this update worth announcing" (version
 * compare + dismissal). This one answers "may the banner occupy pixels given what is on screen".
 *
 * `UpdateBannerHost` puts the banner in a `Column` above the app content with the content in a
 * `Box(weight(1f))`, so the banner does not float over the UI — it *shrinks* it. On a browse screen
 * that is invisible; over a full-bleed player it pushes the picture down and letterboxes it.
 *
 * Found while fixing the mobile twin, where the same structure squeezed a Picture-in-Picture window
 * down to a sliver of video (2026-08-17). TV has no PiP, so only the full-screen player case
 * applies here — but it is the same defect and the same fix.
 */
internal object UpdateBannerVisibilityPolicy {
    fun mayOccupyLayout(
        hasUpdate: Boolean,
        bannerRequested: Boolean,
        playerOnScreen: Boolean
    ): Boolean {
        if (!hasUpdate || !bannerRequested) return false
        if (playerOnScreen) return false
        return true
    }
}
