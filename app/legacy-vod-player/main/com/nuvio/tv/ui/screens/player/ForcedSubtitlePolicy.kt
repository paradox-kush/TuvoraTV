package com.nuvio.tv.ui.screens.player

/**
 * Decides whether automatic subtitle selection runs in forced-only mode.
 *
 * "Forced subtitles only" exists to translate the foreign lines inside content you can otherwise
 * follow, so the setting is conditional on the audio: when the audio is in a language the viewer
 * does NOT read, full subtitles are what they actually want, and falling back to ordinary
 * selection is correct rather than a bug.
 *
 * The language matching itself stays with the caller — it is heuristic, and drags in media3 and
 * the language tables. What lives here is the decision table those heuristics feed, which is the
 * part with a right answer worth pinning down.
 */
internal object ForcedSubtitlePolicy {

    data class Input(
        /** The "forced subtitles only" preference. */
        val useForcedSubtitles: Boolean,
        /** True once an audio track has been selected; nothing can be decided before that. */
        val hasSelectedAudioTrack: Boolean,
        /** The viewer's first preferred subtitle language, or null when they have none set. */
        val primaryTarget: String?,
        /**
         * The audio track's language once resolved, or **null when it could not be established** —
         * the track carries no language tag, or a placeholder like `und`, and nothing could be
         * inferred from its name or id. Plenty of rips ship exactly this.
         */
        val resolvedAudioLanguage: String?,
        /** Whether [resolvedAudioLanguage] matches [primaryTarget], regional variants allowed. */
        val audioMatchesPrimaryTarget: Boolean,
        /** Whether the selected audio is the one the viewer's audio preferences asked for. */
        val audioMatchesResolvedPreferredAudio: Boolean,
    )

    sealed interface Decision {
        /** Audio track not known yet. Decide nothing and keep the latch open. */
        data object Defer : Decision

        /** Only a forced subtitle for [target] may be auto-selected; otherwise show none. */
        data class ForcedOnly(val target: String) : Decision

        /** Ordinary preferred-language selection, forced or not. */
        data object Normal : Decision
    }

    fun evaluate(input: Input): Decision {
        if (!input.useForcedSubtitles) return Decision.Normal
        if (!input.hasSelectedAudioTrack) return Decision.Defer

        if (input.primaryTarget != null) {
            return when {
                input.audioMatchesPrimaryTarget -> Decision.ForcedOnly(input.primaryTarget)
                // An unknown language is not a foreign one. Treating it as foreign is what let a
                // full subtitle be downloaded and switched on for anyone whose file happened to
                // ship an untagged audio track, despite them asking for forced only. With nothing
                // to contradict the viewer's explicit choice, the choice stands.
                input.resolvedAudioLanguage == null -> Decision.ForcedOnly(input.primaryTarget)
                else -> Decision.Normal
            }
        }

        val inferred = input.resolvedAudioLanguage
        return if (input.audioMatchesResolvedPreferredAudio && inferred != null) {
            Decision.ForcedOnly(inferred)
        } else {
            Decision.Normal
        }
    }
}
