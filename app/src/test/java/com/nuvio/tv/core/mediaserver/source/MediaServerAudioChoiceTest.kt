package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.FakeClient
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.client.PlaybackNegotiation
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaStreamDto
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.policy.ServerAudioChoicePolicy
import com.nuvio.tv.core.mediaserver.source
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Owner decision 2026-10-06: a server-built stream carries Tuvora's audio-language choice, falling back to the server's default track. */
class MediaServerAudioChoiceTest {
    @After
    fun reset() { MediaServerItemRegistry.reset(); MediaServerPlaybackSessions.reset() }

    private val client = FakeClient()
    private val rig = TestRig(clientFactory = { client }).also { r ->
        val e = entry(); r.store.applyFromRemote(1, listOf(e)); r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }
    private fun deferred() = MediaServerIds.deferredUrl("jellyfin:$M:$U", "m1", "srcA")
    private fun pref(vararg langs: String) = ServerAudioChoicePolicy.Preference(languages = { langs.toList() }, matches = { lang, wanted -> com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils.matchesLanguageCode(lang, wanted) })

    /** A source the server must transcode (direct play refused - the MPEG-2 case), with an English default and a Spanish track. */
    private fun transcoded() = source("srcA", direct = false, stream = false).let {
        it.copy(
            defaultAudioStreamIndex = 1,
            mediaStreams = it.mediaStreams + listOf(
                MediaStreamDto(index = 1, type = "Audio", codec = "aac", language = "eng", isDefault = true),
                MediaStreamDto(index = 2, type = "Audio", codec = "aac", language = "spa"),
            ),
        )
    }

    @Test
    fun aTranscodeAsksForThePreferredAudioTrackByTheAppsOwnLanguageMatching() = runTest {
        client.negotiation = PlaybackNegotiation(listOf(transcoded()), "ps")
        val url = MediaServerStreamSourceProvider(rig.store, rig.services, audioPreference = pref("es")).resolveDeferredUrl(deferred(), forceMint = false)
        assertEquals("negotiated twice: the default, then the preferred track", 2, client.playbackRequests.size)
        assertNull("the first negotiation leaves the choice to the server", client.playbackRequests[0].second.audioStreamIndex)
        assertEquals("Spanish (spa) matches the app es preference", 2, client.playbackRequests[1].second.audioStreamIndex)
        assertEquals("the server own transcode URL", "http://nas:8096/videos/i/master.m3u8?MediaSourceId=src1&ApiKey=SERVER-BUILT", url)
    }

    @Test
    fun theServerDefaultStandsWhenNoPreferenceMatchesOrItIsAlreadyTheDefault() = runTest {
        client.negotiation = PlaybackNegotiation(listOf(transcoded()), "ps")
        MediaServerStreamSourceProvider(rig.store, rig.services, audioPreference = pref("de", "ja")).resolveDeferredUrl(deferred(), false)
        assertEquals("no match: one negotiation", 1, client.playbackRequests.size)
        client.playbackRequests.clear()
        MediaServerStreamSourceProvider(rig.store, rig.services, audioPreference = pref("en")).resolveDeferredUrl(deferred(), false)
        assertEquals("the preference IS the default: one negotiation", 1, client.playbackRequests.size)
    }

    @Test
    fun aDirectPlayAsksForNothingBecauseThePlayerSeesEveryTrack() = runTest {
        client.negotiation = PlaybackNegotiation(listOf(transcoded().copy(supportsDirectPlay = true, supportsDirectStream = true)), "ps")
        MediaServerStreamSourceProvider(rig.store, rig.services, audioPreference = pref("es")).resolveDeferredUrl(deferred(), false)
        assertEquals("direct play: one negotiation", 1, client.playbackRequests.size)
        assertNull("no audio index asked", client.playbackRequests.single().second.audioStreamIndex)
    }

    @Test
    fun noPreferenceMeansTheServerDecides() = runTest {
        client.negotiation = PlaybackNegotiation(listOf(transcoded()), "ps")
        MediaServerStreamSourceProvider(rig.store, rig.services).resolveDeferredUrl(deferred(), false)
        assertEquals("one negotiation", 1, client.playbackRequests.size)
    }
}
