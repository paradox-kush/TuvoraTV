package com.nuvio.tv.core.mediaserver.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAudioChoicePolicyTest {
    private val tracks = listOf(ServerAudioChoicePolicy.Track(1, "eng"), ServerAudioChoicePolicy.Track(2, "spa"), ServerAudioChoicePolicy.Track(3, "fra"))
    private val exact: (String?, String) -> Boolean = { lang, wanted -> lang == wanted }

    @Test
    fun aPreferredLanguageThatIsNotTheServerDefaultIsAskedFor() {
        assertEquals("the matching track is requested", 2, ServerAudioChoicePolicy.choose(tracks, serverDefaultIndex = 1, preferred = listOf("spa"), matches = exact))
    }

    @Test
    fun theFirstPreferenceThatMatchesWins() {
        assertEquals("priority order, skipping a language the file lacks", 3, ServerAudioChoicePolicy.choose(tracks, 1, listOf("deu", "fra", "spa"), exact))
    }

    @Test
    fun theServerDefaultStandsWhenNoPreferenceMatches() {
        assertNull("nothing to ask: the server default track is used", ServerAudioChoicePolicy.choose(tracks, 1, listOf("deu", "jpn"), exact))
        assertNull("no preference at all", ServerAudioChoicePolicy.choose(tracks, 1, emptyList(), exact))
    }

    @Test
    fun nothingIsAskedWhenThePreferredTrackIsAlreadyTheServerDefault() {
        assertNull("no second negotiation for the same track", ServerAudioChoicePolicy.choose(tracks, 2, listOf("spa"), exact))
    }

    @Test
    fun withoutAKnownServerDefaultThePreferredTrackIsStillAsked() {
        assertEquals("unknown default", 1, ServerAudioChoicePolicy.choose(tracks, null, listOf("eng"), exact))
        assertNull("no audio tracks listed", ServerAudioChoicePolicy.choose(emptyList(), 1, listOf("eng"), exact))
    }
}
