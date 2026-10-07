package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.contracts.PlaybackPlayMethod
import com.nuvio.tv.core.mediaserver.policy.PlaybackDecisionPolicy.Plan
import com.nuvio.tv.core.mediaserver.policy.PlaybackDecisionPolicy.SourceFacts
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class PlaybackDecisionPolicyTest {
    private fun source(
        id: String? = "src1", protocol: String? = "File", container: String? = "mkv",
        direct: Boolean = true, stream: Boolean = true, transcode: Boolean = true,
        directUrl: String? = null, transcodeUrl: String? = "/videos/1/master.m3u8?MediaSourceId=src1&ApiKey=server-built",
    ) = SourceFacts(id, protocol, container, direct, stream, transcode, directUrl, transcodeUrl)

    @Test
    fun directPlayIsTheDefaultAndPinsTheMediaSource() {
        val d = PlaybackDecisionPolicy.decide(source(), userBitrateCap = null, directPlayFailed = false)
        assertEquals(Plan.StaticStream("src1"), d.plan)
        assertEquals(PlaybackPlayMethod.DIRECT_PLAY, d.method)
    }

    @Test
    fun aUserQualityCapTranscodes() {
        val d = PlaybackDecisionPolicy.decide(source(), userBitrateCap = 4_000_000, directPlayFailed = false)
        assertEquals(PlaybackPlayMethod.TRANSCODE, d.method)
        assertEquals(Plan.ServerUrl("/videos/1/master.m3u8?MediaSourceId=src1&ApiKey=server-built"), d.plan)
    }

    @Test
    fun aFailedDirectPlayFallsBackToATranscode() {
        val d = PlaybackDecisionPolicy.decide(source(), userBitrateCap = null, directPlayFailed = true)
        assertEquals(PlaybackPlayMethod.TRANSCODE, d.method)
    }

    @Test
    fun aCapThatTheServerCannotHonourStillPlays() {
        val d = PlaybackDecisionPolicy.decide(source(transcode = false, transcodeUrl = null), userBitrateCap = 1_000_000, directPlayFailed = false)
        assertEquals("better the original than nothing", PlaybackPlayMethod.DIRECT_PLAY, d.method)
    }

    @Test
    fun aNonFileSourceNeverUsesTheStaticStream() {
        // .strm / remote: Static=true is rejected by the server
        val d = PlaybackDecisionPolicy.decide(source(protocol = "Http", directUrl = "https://cdn.example/x.mp4"), null, false)
        assertEquals(Plan.ServerUrl("https://cdn.example/x.mp4"), d.plan)
        assertEquals(PlaybackPlayMethod.DIRECT_STREAM, d.method)
        val viaTranscode = PlaybackDecisionPolicy.decide(source(protocol = "Http", directUrl = null), null, false)
        assertEquals(PlaybackPlayMethod.TRANSCODE, viaTranscode.method)
        val nothing = PlaybackDecisionPolicy.decide(source(protocol = "Http", directUrl = null, transcodeUrl = null), null, false)
        assertEquals(Plan.NotPlayable(PlaybackDecisionPolicy.Reason.NO_PLAYABLE_PATH), nothing.plan)
        assertNull(nothing.method)
    }

    @Test
    fun aRemuxableButNotDirectPlayableFileIsADirectStream() {
        val d = PlaybackDecisionPolicy.decide(source(direct = false, stream = true), null, false)
        assertEquals(PlaybackPlayMethod.DIRECT_STREAM, d.method)
        assertEquals(Plan.ServerUrl("/videos/1/master.m3u8?MediaSourceId=src1&ApiKey=server-built"), d.plan)
        val noUrl = PlaybackDecisionPolicy.decide(source(direct = false, stream = true, transcodeUrl = null), null, false)
        assertEquals(Plan.StaticStream("src1"), noUrl.plan)
    }

    @Test
    fun onlyTranscodableMeansTranscode() {
        val d = PlaybackDecisionPolicy.decide(source(direct = false, stream = false), null, false)
        assertEquals(PlaybackPlayMethod.TRANSCODE, d.method)
        val none = PlaybackDecisionPolicy.decide(source(direct = false, stream = false, transcode = false), null, false)
        assertEquals(Plan.NotPlayable(PlaybackDecisionPolicy.Reason.NO_PLAYABLE_PATH), none.plan)
    }

    @Test
    fun aMissingProtocolIsTreatedAsAFile() {
        assertEquals(PlaybackPlayMethod.DIRECT_PLAY, PlaybackDecisionPolicy.decide(source(protocol = null), null, false).method)
    }

    @Test
    fun wireMethodNamesAndTicks() {
        assertEquals("DirectPlay", PlaybackDecisionPolicy.wireMethod(PlaybackPlayMethod.DIRECT_PLAY))
        assertEquals("DirectStream", PlaybackDecisionPolicy.wireMethod(PlaybackPlayMethod.DIRECT_STREAM))
        assertEquals("Transcode", PlaybackDecisionPolicy.wireMethod(PlaybackPlayMethod.TRANSCODE))
        assertEquals("DirectPlay", PlaybackDecisionPolicy.wireMethod(null))
        assertEquals(1_000L, PlaybackDecisionPolicy.ticksToMs(10_000_000L))
        assertEquals(10_000_000L, PlaybackDecisionPolicy.msToTicks(1_000L))
    }

    private fun offer(sp: Long?, spAt: Long? = 2_000, tp: Long? = 0, tpAt: Long? = 1_000, dur: Long? = 7_200_000) =
        PlaybackDecisionPolicy.resumeOffer(sp, spAt, tp, tpAt, dur)

    @Test
    fun watchedElsewhereOffersTheServerPosition() {
        assertEquals(PlaybackDecisionPolicy.ResumeOffer(1_800_000), offer(1_800_000, spAt = 5_000, tp = 600_000, tpAt = 1_000))
        assertEquals("no Tuvora record at all: the server's place is simply the start", PlaybackDecisionPolicy.ResumeOffer(1_800_000, startAutomatically = true), offer(1_800_000, tp = null, tpAt = null))
        assertEquals("a Tuvora record under 10 s is no record", PlaybackDecisionPolicy.ResumeOffer(1_800_000, startAutomatically = true), offer(1_800_000, tp = 5_000, tpAt = 1_000))
    }

    @Test
    fun noOfferWhenTuvoraIsNewerOrThePositionsAgree() {
        assertNull("Tuvora's record is newer", offer(1_800_000, spAt = 500, tp = 600_000, tpAt = 1_000))
        assertNull("within 30 s", offer(1_800_000, tp = 1_790_000))
        assertNull("less than 10 s in", offer(5_000))
        assertNull(offer(null))
        assertNull("server has it practically finished", offer(7_000_000))
    }

    @Test
    fun withoutTimestampsTheFurtherPositionWins() {
        assertEquals(PlaybackDecisionPolicy.ResumeOffer(1_800_000), offer(1_800_000, spAt = null, tp = 100_000, tpAt = null))
        assertNull(offer(100_000, spAt = null, tp = 1_800_000, tpAt = null))
    }
}
