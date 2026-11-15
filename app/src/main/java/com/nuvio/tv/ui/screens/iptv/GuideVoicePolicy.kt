package com.nuvio.tv.ui.screens.iptv

/**
 * B12 of the IPTV redesign (2026-11-15, A7): Tuvora's playful voice in the quiet moments only. A joke sits beside a
 * plain fact line and never replaces it; loading and failure copy stays plain (a failure is when the viewer needs
 * facts, not a quip). Pure, so the rule is tested.
 */
internal object GuideVoicePolicy {
    /** A7 default. Flip to false and every quip disappears, leaving the plain lines. */
    const val PLAYFUL = true

    enum class Moment {
        EMPTY_FAVORITES, EMPTY_RECENT, SEARCH_PROMPT, SEARCH_MISS, EMPTY_GROUP, EMPTY_PLAYLIST,
        IDLE_PREVIEW, NO_GUIDE,
        LOADING, FAILED,
    }

    private val plainOnly = setOf(Moment.LOADING, Moment.FAILED, Moment.SEARCH_PROMPT)

    /** Whether [moment] carries a quip above its plain line. */
    fun joking(moment: Moment, playful: Boolean = PLAYFUL): Boolean = playful && moment !in plainOnly

    fun momentOf(kind: GuideEmptyStatePolicy.Kind): Moment = when (kind) {
        GuideEmptyStatePolicy.Kind.FAVORITES_HINT -> Moment.EMPTY_FAVORITES
        GuideEmptyStatePolicy.Kind.RECENT_EMPTY -> Moment.EMPTY_RECENT
        GuideEmptyStatePolicy.Kind.SEARCH_PROMPT -> Moment.SEARCH_PROMPT
        GuideEmptyStatePolicy.Kind.SEARCH_NO_MATCH -> Moment.SEARCH_MISS
        GuideEmptyStatePolicy.Kind.PLAYLIST_EMPTY -> Moment.EMPTY_PLAYLIST
        GuideEmptyStatePolicy.Kind.NO_CHANNELS -> Moment.EMPTY_GROUP
    }
}
