package com.nuvio.tv.core.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDefaultEnginePolicyTest {

    @Test
    fun `live defaults to mpv when the lane exists`() {
        // Product decision (1.5.8): live TV opens on the ffmpeg/libmpv engine by default — the
        // fleet's live freezes concentrate on ExoPlayer's hardware TS path (Fire TV/MediaTek
        // 20-40% freeze-per-start across 1.5.5-1.5.7), while the same streams play clean on mpv
        // (iPhone / Android phones already default live to libmpv). Flipping live back to
        // ExoPlayer must consciously change this named policy, not fall out of a refactor.
        assertTrue(LiveDefaultEnginePolicy.preferMpvForLive(mpvAvailable = true))
    }

    @Test
    fun `live stays on exoplayer when the mpv lane is unavailable`() {
        // Devices without the mpv lane (livePlaybackMpvAvailable=false) must keep working on
        // ExoPlayer — the default never selects an engine that cannot exist on the device.
        assertFalse(LiveDefaultEnginePolicy.preferMpvForLive(mpvAvailable = false))
    }
}
