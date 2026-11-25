package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B285: Retry on a torrent stream crashed every time — stopTorrentStream() nulls currentInfoHash and
 * currentFileIdx, and the relaunch then read currentInfoHash!!. The fake below tears down exactly like the
 * controller does.
 */
class TorrentRetryTest {

    private class FakeController(var isTorrent: Boolean, var infoHash: String?, var fileIdx: Int?) {
        val calls = mutableListOf<String>()
        fun current() = if (isTorrent) infoHash?.let { TorrentRetryTarget(it, fileIdx) } else null
        fun teardown() { calls += "teardown"; isTorrent = false; infoHash = null; fileIdx = null }
    }

    @Test
    fun retryRelaunchesTheSameTorrentAfterTeardownClearedIt() {
        val c = FakeController(isTorrent = true, infoHash = "abc123", fileIdx = 4)
        var relaunched: TorrentRetryTarget? = null
        val handled = retryTorrent({ c.current() }, { c.teardown() }) { relaunched = it; c.calls += "relaunch" }
        assertTrue("a torrent retry is handled", handled)
        assertEquals("relaunches the hash and file index read before teardown", TorrentRetryTarget("abc123", 4), relaunched)
        assertEquals("tears down before relaunching", listOf("teardown", "relaunch"), c.calls)
    }

    @Test
    fun notATorrentLeavesRetryToTheNormalPath() {
        val c = FakeController(isTorrent = false, infoHash = null, fileIdx = null)
        val handled = retryTorrent({ c.current() }, { c.teardown() }) { error("must not relaunch") }
        assertFalse("not handled", handled)
        assertEquals("no teardown here", emptyList<String>(), c.calls)
    }
}
