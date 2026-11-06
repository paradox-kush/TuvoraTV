package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.livepause.LivePausePolicy.EngineCachePlan
import com.nuvio.tv.core.livepause.LivePausePolicy.GoLiveAction
import com.nuvio.tv.core.livepause.LivePausePolicy.HlsVariant
import com.nuvio.tv.core.livepause.LivePausePolicy.RefuseReason
import com.nuvio.tv.core.livepause.LivePausePolicy.SpaceVerdict
import com.nuvio.tv.core.livepause.LivePausePolicy.StartDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePausePolicyTest {
    private val mb = 1024L * 1024
    private val reserve = LivePausePolicy.FREE_SPACE_RESERVE_BYTES
    private val br = 8_000_000L

    private fun ch(live: Boolean = true, catchUp: Boolean = false, archive: Boolean = false) =
        LivePauseChannel(isLive = live, isCatchUpPlayback = catchUp, hasProviderArchive = archive)

    private fun caps(disk: Boolean = false, engine: Boolean = false) = LivePauseCapabilities(disk, engine)

    // ---- mechanism

    @Test
    fun `a channel that is not live has no pause mechanism`() {
        assertEquals("VOD", LivePauseMechanism.UNAVAILABLE, LivePausePolicy.mechanism(ch(live = false), caps(true, true)))
    }

    @Test
    fun `a catch-up replay already seeks so it has no pause mechanism even with an archive`() {
        assertEquals("replay", LivePauseMechanism.UNAVAILABLE, LivePausePolicy.mechanism(ch(catchUp = true, archive = true), caps(true, true)))
    }

    @Test
    fun `a provider archive wins over a local buffer`() {
        assertEquals("archive", LivePauseMechanism.PROVIDER_CATCHUP, LivePausePolicy.mechanism(ch(archive = true), caps(true, true)))
    }

    @Test
    fun `disk buffer wins over the engine back buffer`() {
        assertEquals("disk", LivePauseMechanism.DISK_BUFFER, LivePausePolicy.mechanism(ch(), caps(true, true)))
    }

    @Test
    fun `engine back buffer when only the engine can do it`() {
        assertEquals("engine", LivePauseMechanism.ENGINE_BACK_BUFFER, LivePausePolicy.mechanism(ch(), caps(false, true)))
    }

    @Test
    fun `nothing available when no capability`() {
        assertEquals("none", LivePauseMechanism.UNAVAILABLE, LivePausePolicy.mechanism(ch(), caps()))
    }

    // ---- sizing

    @Test
    fun `bytesFor is bitrate over eight times seconds times the container overhead`() {
        assertEquals("1 min at 8 Mbps", 66_000_000L, LivePausePolicy.bytesFor(60_000, br))
        assertEquals("unknown bitrate uses the fallback", 66_000_000L, LivePausePolicy.bytesFor(60_000, null))
        assertEquals("zero bitrate uses the fallback", 66_000_000L, LivePausePolicy.bytesFor(60_000, 0L))
        assertEquals("negative duration clamps", 0L, LivePausePolicy.bytesFor(-5, br))
    }

    @Test
    fun `durationFor is the inverse of bytesFor`() {
        assertEquals("1 min", 60_000L, LivePausePolicy.durationFor(66_000_000L, br))
        assertEquals("fallback", 60_000L, LivePausePolicy.durationFor(66_000_000L, null))
        assertEquals("zero bytes", 0L, LivePausePolicy.durationFor(0, br))
        assertEquals("negative bytes clamp", 0L, LivePausePolicy.durationFor(-1, br))
    }

    // ---- diskStart

    @Test
    fun `plenty of space starts the full default window`() {
        val free = reserve + LivePausePolicy.bytesFor(LivePausePolicy.DEFAULT_KEEP_MS, br) + mb
        assertEquals(
            "full window",
            StartDecision.Start(LivePausePolicy.DEFAULT_KEEP_MS, LivePausePolicy.bytesFor(LivePausePolicy.DEFAULT_KEEP_MS, br)),
            LivePausePolicy.diskStart(free, br),
        )
    }

    @Test
    fun `the window shrinks to what fits above the reserve`() {
        val free = reserve + LivePausePolicy.bytesFor(300_000, br)
        val d = LivePausePolicy.diskStart(free, br) as StartDecision.Start
        assertEquals("5 min fits", 300_000L, d.keepMs)
        assertEquals("budget is that window's bytes", LivePausePolicy.bytesFor(300_000, br), d.budgetBytes)
    }

    @Test
    fun `an explicitly smaller request is honoured`() {
        val free = reserve + LivePausePolicy.bytesFor(LivePausePolicy.DEFAULT_KEEP_MS, br) * 2
        val d = LivePausePolicy.diskStart(free, br, requestedKeepMs = 120_000) as StartDecision.Start
        assertEquals("2 min", 120_000L, d.keepMs)
    }

    @Test
    fun `exactly the minimum window still starts`() {
        val free = reserve + LivePausePolicy.bytesFor(LivePausePolicy.MIN_KEEP_MS, br)
        val d = LivePausePolicy.diskStart(free, br) as StartDecision.Start
        assertEquals("minimum", LivePausePolicy.MIN_KEEP_MS, d.keepMs)
    }

    @Test
    fun `under the minimum window refuses with NO_SPACE`() {
        val free = reserve + LivePausePolicy.bytesFor(30_000, br)
        assertEquals("30 s is not worth it", StartDecision.Refuse(RefuseReason.NO_SPACE), LivePausePolicy.diskStart(free, br))
    }

    @Test
    fun `free space below the reserve refuses with NO_SPACE`() {
        assertEquals("below reserve", StartDecision.Refuse(RefuseReason.NO_SPACE), LivePausePolicy.diskStart(reserve - 1, br))
        assertEquals("zero free", StartDecision.Refuse(RefuseReason.NO_SPACE), LivePausePolicy.diskStart(0, br))
    }

    @Test
    fun `unknown bitrate sizes with the fallback`() {
        val free = reserve + LivePausePolicy.bytesFor(300_000, null)
        val d = LivePausePolicy.diskStart(free, null) as StartDecision.Start
        assertEquals("fallback sizing", 300_000L, d.keepMs)
    }

    // ---- beforeWrite

    @Test
    fun `a write that leaves the reserve intact and fits the budget is allowed`() {
        assertEquals("ok", SpaceVerdict.WRITE, LivePausePolicy.beforeWrite(reserve + 10 * mb, 10 * mb, 50 * mb, 100 * mb))
    }

    @Test
    fun `eating into the reserve trims when something is buffered`() {
        assertEquals("trim", SpaceVerdict.TRIM_OLDEST, LivePausePolicy.beforeWrite(reserve + 10 * mb - 1, 10 * mb, 50 * mb, 100 * mb))
    }

    @Test
    fun `eating into the reserve with nothing buffered stops with no space`() {
        assertEquals("stop", SpaceVerdict.STOP_NO_SPACE, LivePausePolicy.beforeWrite(reserve - 1, 10 * mb, 0, 100 * mb))
    }

    @Test
    fun `over the budget trims the oldest`() {
        assertEquals("over", SpaceVerdict.TRIM_OLDEST, LivePausePolicy.beforeWrite(10_000 * mb, 10 * mb, 95 * mb + 1, 100 * mb))
    }

    @Test
    fun `exactly at the budget is allowed`() {
        assertEquals("edge", SpaceVerdict.WRITE, LivePausePolicy.beforeWrite(10_000 * mb, 10 * mb, 90 * mb, 100 * mb))
    }

    @Test
    fun `a single piece bigger than the budget with nothing buffered is written instead of trimming nothing`() {
        assertEquals("nothing to trim", SpaceVerdict.WRITE, LivePausePolicy.beforeWrite(10_000 * mb, 10 * mb, 0, 5 * mb))
    }

    // ---- retention

    @Test
    fun `piecesToDrop trims from the front until the rest fits the keep`() {
        val six = List(10) { 6_000L }
        assertEquals("60 s down to 30 s", 5, LivePausePolicy.piecesToDrop(six, 30_000))
        assertEquals("already within", 0, LivePausePolicy.piecesToDrop(six, 60_000))
        assertEquals("just over", 1, LivePausePolicy.piecesToDrop(six, 59_999))
    }

    @Test
    fun `piecesToDrop always keeps at least three pieces`() {
        assertEquals("keeps 3 of 5", 2, LivePausePolicy.piecesToDrop(List(5) { 6_000L }, 1_000))
        assertEquals("3 of 3", 0, LivePausePolicy.piecesToDrop(List(3) { 6_000L }, 1_000))
        assertEquals("empty", 0, LivePausePolicy.piecesToDrop(emptyList(), 1_000))
        assertEquals("custom minimum", 3, LivePausePolicy.piecesToDrop(List(5) { 6_000L }, 1_000, minPieces = 2))
    }

    // ---- engine plans

    private val normal = EngineCachePlan(maxBytes = 32 * mb, maxBackBytes = 16 * mb, cacheSecs = 30)

    @Test
    fun `engine live plan raises only the back buffer`() {
        val p = LivePausePolicy.engineLivePlan(256 * mb, normal, br)
        assertEquals("back is half the budget, capped by ten minutes", 128 * mb, p.maxBackBytes)
        assertEquals("forward unchanged while watching live", normal.maxBytes, p.maxBytes)
        assertEquals("cache secs unchanged", normal.cacheSecs, p.cacheSecs)
    }

    @Test
    fun `engine live plan back buffer is capped by the keep window`() {
        val p = LivePausePolicy.engineLivePlan(256 * mb, normal, br, keepMs = 60_000)
        assertEquals("one minute of bytes", 66_000_000L, p.maxBackBytes)
    }

    @Test
    fun `engine paused plan gives the rest of the budget to the forward cache`() {
        val p = LivePausePolicy.enginePausedPlan(256 * mb, normal, br)
        assertEquals("back", 128 * mb, p.maxBackBytes)
        assertEquals("forward = budget minus back", 128 * mb, p.maxBytes)
        assertEquals("cache secs from the forward bytes", 122L, p.cacheSecs)
    }

    @Test
    fun `engine paused plan never goes below the normal forward cache`() {
        val p = LivePausePolicy.enginePausedPlan(40 * mb, normal, br)
        assertEquals("forward floor", normal.maxBytes, p.maxBytes)
        assertTrue("cache secs floor", p.cacheSecs >= normal.cacheSecs)
    }

    // ---- go live

    @Test
    fun `go live per mechanism`() {
        assertEquals("disk always retunes", GoLiveAction.RETUNE, LivePausePolicy.goLive(LivePauseMechanism.DISK_BUFFER, true))
        assertEquals("disk always retunes even unhealthy", GoLiveAction.RETUNE, LivePausePolicy.goLive(LivePauseMechanism.DISK_BUFFER, false))
        assertEquals("engine healthy seeks", GoLiveAction.SEEK_TO_EDGE, LivePausePolicy.goLive(LivePauseMechanism.ENGINE_BACK_BUFFER, true))
        assertEquals("engine unhealthy retunes", GoLiveAction.RETUNE, LivePausePolicy.goLive(LivePauseMechanism.ENGINE_BACK_BUFFER, false))
        assertEquals("archive retunes", GoLiveAction.RETUNE, LivePausePolicy.goLive(LivePauseMechanism.PROVIDER_CATCHUP, true))
    }

    // ---- variants

    @Test
    fun `capture bitrate cap by memory tier`() {
        assertEquals("low", 6_000_000L, LivePausePolicy.maxCaptureBitrateBps(true))
        assertEquals("normal", 15_000_000L, LivePausePolicy.maxCaptureBitrateBps(false))
    }

    private fun v(bw: Long) = HlsVariant("v$bw.m3u8", bw, null)

    @Test
    fun `pickVariant does not take the highest bandwidth above the cap`() {
        val picked = LivePausePolicy.pickVariant(listOf(v(1_000_000), v(4_000_000), v(20_000_000), v(8_000_000)), 15_000_000)
        assertEquals("best that fits, not the 20 Mbps one", 8_000_000L, picked?.bandwidthBps)
    }

    @Test
    fun `pickVariant includes a variant exactly at the cap`() {
        assertEquals("edge", 15_000_000L, LivePausePolicy.pickVariant(listOf(v(15_000_000), v(2_000_000)), 15_000_000)?.bandwidthBps)
    }

    @Test
    fun `pickVariant falls back to the lowest when none fits`() {
        assertEquals("lowest", 20_000_000L, LivePausePolicy.pickVariant(listOf(v(30_000_000), v(20_000_000), v(25_000_000)), 6_000_000)?.bandwidthBps)
    }

    @Test
    fun `pickVariant on nothing is null`() {
        assertNull("empty", LivePausePolicy.pickVariant(emptyList(), 15_000_000))
    }
}
