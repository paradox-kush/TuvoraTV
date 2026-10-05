package com.nuvio.tv.playback.mpv

/**
 * T6 (W2 device pass): the font libmpv draws subtitles with in the clean player.
 *
 * On Android libass has no system font provider (no fontconfig), so mpv draws text only with the
 * font it finds itself: `subfont.ttf` looked up in the config dir (mpv `sub/ass_mp.c`,
 * `mp_find_config_file(..., "subfont.ttf")`). The clean adapter runs with `config=no` (no user
 * mpv.conf), and with `--no-config` mpv resolves NO config-dir file at all (`options/path.c`:
 * an empty configdir returns NULL for every lookup) — so a picked subtitle track (HLS WebVTT,
 * embedded SRT/ASS) decoded and drew nothing. The legacy VOD view copied the font and passed a
 * config dir, which is why the same streams showed subtitles there.
 *
 * The fix keeps `config=no` and hands libass the bundled font explicitly: `sub-fonts-dir` takes an
 * absolute path (resolved without the config dir) and `sub-font` names the bundled family.
 */
internal object MpvSubtitleFonts {
    /** Family of the `subfont.ttf` the libmpv AAR ships (its name table). */
    const val BUNDLED_FAMILY = "Droid Sans Fallback"
    const val ASSET_NAME = "subfont.ttf"
    const val DIRECTORY_NAME = "mpv-fonts"

    fun options(fontsDirectory: String): Map<String, String> = linkedMapOf(
        "sub-fonts-dir" to fontsDirectory,
        "sub-font" to BUNDLED_FAMILY,
    )
}
