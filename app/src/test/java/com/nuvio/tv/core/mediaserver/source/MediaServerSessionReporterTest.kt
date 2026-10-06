package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.PlaybackPlayMethod
import com.nuvio.tv.core.contracts.PlaybackSessionState
import com.nuvio.tv.core.mediaserver.FakeClient
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.U
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.PlaybackReportKind
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class MediaServerSessionReporterTest {
    @After
    fun reset() = MediaServerPlaybackSessions.reset()

    private val client = FakeClient()
    private val sourceKey = "jellyfin:$M:$U"
    private fun rig() = TestRig(clientFactory = { client }).also { r ->
        val e = entry()
        r.store.applyFromRemote(1, listOf(e))
        r.credentials.save(e.serverKey, StoredCredential("TOKEN-1"))
    }

    private val videoId = "ms:jellyfin:$M:$U:movie:m1"
    private fun state(pos: Long, dur: Long = 7_200_000, video: String = videoId, provider: String? = "ms", method: PlaybackPlayMethod? = null) =
        PlaybackSessionState(video, video, provider, pos, dur, method)

    private fun reporter(rig: TestRig, invalidated: MutableList<String> = mutableListOf()) =
        MediaServerSessionReporter(rig.store, rig.services, { rig.nowMs }, onReported = { invalidated += it })

    private fun minted(method: PlaybackPlayMethod = PlaybackPlayMethod.TRANSCODE) =
        MediaServerPlaybackSessions.record(MediaServerPlaybackSessions.Session(sourceKey, "m1", "srcA", "ps1", method))

    @Test
    fun itOwnsOnlyServerItemsAndMatchedLanePlays() {
        val r = reporter(rig())
        assertTrue(r.handles(videoId, "ms")); assertTrue(r.handles("tt0133093", "ms-match:$sourceKey")); assertTrue(r.handles(videoId, null))
        assertFalse(r.handles("tt0133093", "xtream-match:x")); assertFalse(r.handles("xtream:a|b:vod:1", "xtream")); assertFalse(r.handles("tt1", "addon:x"))
        assertEquals("mediaserver", r.name)
    }

    @Test
    fun aSessionReportsStartProgressAndStopWithTheMintedPlayMethod() = runTest {
        val rig = rig(); minted(PlaybackPlayMethod.TRANSCODE)
        val invalidated = mutableListOf<String>()
        val r = reporter(rig, invalidated)
        r.onStart(state(0))
        rig.nowMs += 4_000
        r.onProgress(state(4_000), paused = false) // inside the 15 s window: nothing
        rig.nowMs += 12_000
        r.onProgress(state(16_000), paused = false)
        rig.nowMs += 1_000
        r.onProgress(state(17_000), paused = true)
        r.onStop(state(17_000))
        assertEquals(listOf(PlaybackReportKind.START, PlaybackReportKind.PROGRESS, PlaybackReportKind.PROGRESS, PlaybackReportKind.STOPPED), client.reports.map { it.kind })
        assertTrue(client.reports.all { it.playMethod == "Transcode" && it.itemId == "m1" && it.playSessionId == "ps1" && it.mediaSourceId == "srcA" })
        assertEquals(listOf(false, true), client.reports.filter { it.kind == PlaybackReportKind.PROGRESS }.map { it.isPaused })
        assertEquals(160_000_000L, client.reports[1].positionTicks)
        assertEquals("the Home contributor learns the server's rows changed", listOf(sourceKey.substringBeforeLast(':')), invalidated)
    }

    @Test
    fun aServerBuiltPlayEndsItsTranscodeJobWithTheSessionAndADirectPlayHasNone() = runTest {
        val rig = rig(); minted(PlaybackPlayMethod.TRANSCODE)
        val r = reporter(rig)
        r.onStart(state(0)); r.onStop(state(60_000))
        assertEquals("the ffmpeg job is ended best-effort once the Stopped report is out", listOf("ps1"), client.stoppedEncodings)
        MediaServerPlaybackSessions.reset(); client.stoppedEncodings.clear(); client.reports.clear(); minted(PlaybackPlayMethod.DIRECT_PLAY)
        val direct = reporter(rig)
        direct.onStart(state(0)); direct.onStop(state(60_000))
        assertTrue("nothing to end for a direct play", client.stoppedEncodings.isEmpty())
    }

    @Test
    fun thePlayersOwnPlayMethodWinsWhenItKnowsOne() = runTest {
        val rig = rig(); minted(PlaybackPlayMethod.DIRECT_PLAY)
        reporter(rig).onStart(state(0, method = PlaybackPlayMethod.DIRECT_STREAM))
        assertEquals("DirectStream", client.reports.single().playMethod)
    }

    @Test
    fun anUnmintedPlayStillReportsAgainstTheIdAsDirectPlay() = runTest {
        val rig = rig()
        reporter(rig).onStart(state(0))
        val rep = client.reports.single()
        assertEquals("m1", rep.itemId); assertEquals("DirectPlay", rep.playMethod); assertEquals(null, rep.playSessionId)
    }

    @Test
    fun aMatchedLanePlayFindsItsItemThroughTheServersLatestMintedSession() = runTest {
        val rig = rig(); minted()
        reporter(rig).onStart(state(0, video = "tt0133093", provider = "ms-match:$sourceKey"))
        assertEquals("m1", client.reports.single().itemId)
    }

    @Test
    fun stopIsReportedOnceAndNothingFollowsIt() = runTest {
        val rig = rig(); minted()
        val r = reporter(rig)
        r.onStart(state(0)); r.onStop(state(60_000)); r.onStop(state(60_000))
        r.onProgress(state(90_000), false)
        assertEquals(2, client.reports.size)
    }

    @Test
    fun finishingAboveTheServersThresholdMarksNothingExplicitly() = runTest {
        val rig = rig(); minted()
        val r = reporter(rig)
        r.onStart(state(0)); r.onStop(state(6_900_000)) // 95.8%: the server marks it played from the Stopped report itself
        assertTrue("no double-scrobble (the server's Trakt plugin would fire twice)", client.played.isEmpty())
    }

    @Test
    fun finishingBelowTheServersThresholdButAboveTuvorasMarksExplicitlyOnce() = runTest {
        val rig = rig(); minted()
        val r = MediaServerSessionReporter(rig.store, rig.services, { rig.nowMs }, tuvoraFinishedPercent = 80f)
        r.onStart(state(0)); r.onStop(state(6_000_000)) // 83%: Tuvora counts it watched, the server (90%) does not
        assertEquals(listOf("m1" to true), client.played)
    }

    @Test
    fun aFailingServerNeverBreaksPlaybackAndARevokedTokenSignsTheDeviceOut() = runTest {
        val rig = rig(); minted()
        val r = reporter(rig)
        client.failWith = MediaServerException.Unreachable("down")
        r.onStart(state(0)); r.onProgress(state(20_000), false)
        assertTrue(client.reports.isEmpty())
        client.failWith = MediaServerException.Http(401)
        rig.nowMs += 60_000
        r.onProgress(state(80_000), false)
        assertFalse(rig.services.isSignedIn(rig.store.current().single()))
    }

    @Test
    fun aSignedOutDeviceReportsNothing() = runTest {
        val rig = rig(); minted()
        rig.credentials.remove(rig.store.current().single().serverKey)
        reporter(rig).onStart(state(0))
        assertTrue(client.reports.isEmpty())
    }
}
