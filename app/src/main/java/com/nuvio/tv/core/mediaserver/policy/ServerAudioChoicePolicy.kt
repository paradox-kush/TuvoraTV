package com.nuvio.tv.core.mediaserver.policy

/**
 * Which AUDIO track a server-built stream carries (owner decision 2026-10-06): Tuvora's own language preferences
 * first, the server's default track when none of them matches. Only a stream the SERVER builds needs asking -
 * a transcode / repackaged stream carries the one audio track the server chose, and the player cannot switch it afterwards. A
 * direct play exposes every track to the player, whose own language logic (ExoPlayer / mpv preferred languages,
 * falling back to the container's default flag) already behaves the same way, so nothing is asked there.
 * Pure: the caller maps the server's streams into [Track] and supplies the language matcher.
 */
internal object ServerAudioChoicePolicy {
    data class Track(val index: Int, val language: String?)

    /** The user's preference: [languages] in priority order, and the app's own [matches] (track language, wanted language). */
    class Preference(val languages: suspend () -> List<String>, val matches: (String?, String) -> Boolean)

    /**
     * The `AudioStreamIndex` to ask the server for, or null when nothing needs asking: no preference matches an
     * audio track (the server's default stands), or the matching track already IS the server's default.
     */
    fun choose(tracks: List<Track>, serverDefaultIndex: Int?, preferred: List<String>, matches: (String?, String) -> Boolean): Int? {
        for (wanted in preferred) {
            val hit = tracks.firstOrNull { matches(it.language, wanted) } ?: continue
            return hit.index.takeUnless { it == serverDefaultIndex }
        }
        return null
    }
}
