package com.nuvio.tv.player.mpv

/**
 * B116 — which raw libmpv/FFmpeg log modules may reach the device log at all.
 *
 * mpv-android-lib's native layer prints EVERY log message mpv delivers straight to logcat (tag `mpv`,
 * `[%s:%s] %s`), before any Kotlin observer sees it, so those lines cannot be redacted — only not
 * produced. Its default `msg-level` is `all=v`, which prints `[cplayer] Playing: <url>`,
 * `[stream] Opening <url>` and, at error level, `[stream] Failed to open <url>.`; FFmpeg's HLS demuxer
 * warns with segment URLs (`keepalive request failed for '<url>'`). Provider credentials ride in
 * those URLs (Xtream `/timeshift/<user>/<pass>/…`, `get.php?username=&password=`).
 *
 * So raw output is OFF (TV's clean adapter already runs `all=no`), with an allowlist of modules that
 * report decoder/output state and never format a URL: video/audio output and decoders (incl. the
 * `vo/gpu/aimagereader` presentation-fault lines MpvPresentationFaultPolicy watches on TV).
 * Matching follows mpv `common/msg.c`: an entry applies to the module and its `/` sub-modules, and
 * the LAST matching entry wins.
 */
internal object MpvLogLevelPolicy {
    const val MSG_LEVEL = "all=no,vo=v,ao=warn,vd=warn,ad=warn,ffmpeg/video=warn,ffmpeg/audio=warn"

    /** The effective level mpv would apply to [module] under [spec] (default when nothing matches: "status"). */
    fun levelFor(module: String, spec: String = MSG_LEVEL): String {
        var level = "status"
        spec.split(',').forEach { entry ->
            val name = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotEmpty() && matches(module, name)) level = value
        }
        return level
    }

    private fun matches(module: String, name: String): Boolean =
        name == "all" || module == name || module.startsWith("$name/")
}
