package com.nuvio.tv.player.mpv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPresentationFaultPolicyTest {

    @Test
    fun `classifies the device-proven AImageReader presentation failures`() {
        assertTrue(
            MpvPresentationFaultPolicy.isPresentationFault(
                "vo/gpu/aimagereader",
                "Waiting for frame timed out!",
            )
        )
        assertTrue(
            MpvPresentationFaultPolicy.isPresentationFault(
                "vo/gpu/aimagereader",
                "acquireLatestImage failed: -30001",
            )
        )
    }

    @Test
    fun `does not classify decoder or ordinary buffering logs as presentation faults`() {
        assertFalse(MpvPresentationFaultPolicy.isPresentationFault("vd", "Waiting for frame timed out!"))
        assertFalse(MpvPresentationFaultPolicy.isPresentationFault("cplayer", "Enter buffering"))
    }
}
