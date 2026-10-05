package com.nuvio.tv.ui.screens.settings

/**
 * F35: the words for the per-playlist catch-up container preference, byte-for-byte the phone's and
 * desktop's (NuvioMobile XtreamContentSettingsPage.CatchUpSettings), so one name works on every
 * platform and in support replies. TV had the setting all along as "Catch-up container: Prefer TS",
 * which a phone user could not recognise.
 */
internal object CatchUpContainerCopy {
    const val TITLE = "Prefer m3u8 (enables scrubbing)"

    fun value(preferM3u8: Boolean): String = if (preferM3u8) "On" else "Off"

    fun description(preferM3u8: Boolean): String = if (preferM3u8) {
        "Asks the panel for HLS first, so replays have a progress bar. Falls back to TS."
    } else {
        "Asks the panel for TS first — the more reliable default. Replays usually can't scrub."
    }
}
