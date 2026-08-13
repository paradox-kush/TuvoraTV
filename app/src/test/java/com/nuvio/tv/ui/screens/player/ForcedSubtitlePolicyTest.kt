package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.ForcedSubtitlePolicy.Decision
import com.nuvio.tv.ui.screens.player.ForcedSubtitlePolicy.Input
import org.junit.Assert.assertEquals
import org.junit.Test

class ForcedSubtitlePolicyTest {

    /**
     * Defaults describe the ordinary happy case: forced-only is on, the viewer prefers English
     * subtitles, and the file's audio track is tagged English.
     */
    private fun input(
        useForcedSubtitles: Boolean = true,
        hasSelectedAudioTrack: Boolean = true,
        primaryTarget: String? = "eng",
        resolvedAudioLanguage: String? = "eng",
        audioMatchesPrimaryTarget: Boolean = true,
        audioMatchesResolvedPreferredAudio: Boolean = true,
    ) = Input(
        useForcedSubtitles = useForcedSubtitles,
        hasSelectedAudioTrack = hasSelectedAudioTrack,
        primaryTarget = primaryTarget,
        resolvedAudioLanguage = resolvedAudioLanguage,
        audioMatchesPrimaryTarget = audioMatchesPrimaryTarget,
        audioMatchesResolvedPreferredAudio = audioMatchesResolvedPreferredAudio,
    )

    @Test
    fun `setting off means ordinary selection`() {
        assertEquals(Decision.Normal, ForcedSubtitlePolicy.evaluate(input(useForcedSubtitles = false)))
    }

    @Test
    fun `nothing is decided before an audio track is selected`() {
        assertEquals(Decision.Defer, ForcedSubtitlePolicy.evaluate(input(hasSelectedAudioTrack = false)))
    }

    @Test
    fun `audio in the viewer's own language means forced-only`() {
        assertEquals(
            Decision.ForcedOnly("eng"),
            ForcedSubtitlePolicy.evaluate(input()),
        )
    }

    /**
     * Deliberate, not a bug: forced subtitles translate the foreign lines inside content you can
     * follow. Japanese audio with an English subtitle preference wants full subtitles.
     */
    @Test
    fun `audio in a known foreign language falls back to full subtitles`() {
        assertEquals(
            Decision.Normal,
            ForcedSubtitlePolicy.evaluate(
                input(resolvedAudioLanguage = "jpn", audioMatchesPrimaryTarget = false),
            ),
        )
    }

    /**
     * Reported on Discord: "Subtitles are playing automatically even though I have just forced subs
     * enabled... It's not every time, but it's sometimes."
     *
     * The "sometimes" is the file. When a rip's audio track carries no language tag, the language
     * cannot be matched against the target, which read as "foreign audio" and silently re-enabled
     * ordinary selection — so a full English subtitle got downloaded and switched on despite the
     * setting. An unknown language is not a foreign one: with nothing to contradict the viewer's
     * explicit choice, the choice stands.
     */
    @Test
    fun `untagged audio honours forced-only instead of falling back to full subtitles`() {
        assertEquals(
            Decision.ForcedOnly("eng"),
            ForcedSubtitlePolicy.evaluate(
                input(resolvedAudioLanguage = null, audioMatchesPrimaryTarget = false),
            ),
        )
    }

    @Test
    fun `untagged audio still falls back when the setting is off`() {
        assertEquals(
            Decision.Normal,
            ForcedSubtitlePolicy.evaluate(
                input(
                    useForcedSubtitles = false,
                    resolvedAudioLanguage = null,
                    audioMatchesPrimaryTarget = false,
                ),
            ),
        )
    }

    @Test
    fun `no subtitle preference uses the preferred audio's own language`() {
        assertEquals(
            Decision.ForcedOnly("jpn"),
            ForcedSubtitlePolicy.evaluate(
                input(
                    primaryTarget = null,
                    resolvedAudioLanguage = "jpn",
                    audioMatchesPrimaryTarget = false,
                ),
            ),
        )
    }

    @Test
    fun `no subtitle preference and unwanted audio means ordinary selection`() {
        assertEquals(
            Decision.Normal,
            ForcedSubtitlePolicy.evaluate(
                input(
                    primaryTarget = null,
                    resolvedAudioLanguage = "jpn",
                    audioMatchesPrimaryTarget = false,
                    audioMatchesResolvedPreferredAudio = false,
                ),
            ),
        )
    }

    /** No preference and no resolvable audio language leaves nothing to force against. */
    @Test
    fun `no subtitle preference and untagged audio means ordinary selection`() {
        assertEquals(
            Decision.Normal,
            ForcedSubtitlePolicy.evaluate(
                input(
                    primaryTarget = null,
                    resolvedAudioLanguage = null,
                    audioMatchesPrimaryTarget = false,
                ),
            ),
        )
    }
}
