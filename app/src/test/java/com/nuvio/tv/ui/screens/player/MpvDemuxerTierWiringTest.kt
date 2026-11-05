package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.functionBody
import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.source
import org.junit.Assert.assertTrue
import org.junit.Test

/** C10: the libmpv demuxer cache is tiered by device RAM instead of a flat 64+64 MiB. */
class MpvDemuxerTierWiringTest {
    @Test
    fun `the mpv view sizes its demuxer cache from the memory tier`() {
        val view = source("NuvioMpvSurfaceView.kt")
        assertTrue(view.contains("mpvDemuxerBudget(lowRam = com.nuvio.tv.core.util.DeviceClass.isLowRam(context))"))
        assertTrue(view.contains("DeviceClass.isLowRam("))
        assertTrue("no flat 64 MiB back buffer", !view.contains("\"demuxer-max-back-bytes\", \"\${64 * 1024 * 1024}\""))
    }
}
