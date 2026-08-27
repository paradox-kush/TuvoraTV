package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveGuideSurfacePolicyTest {

    @Test
    fun `proven Amazon MT8696 family uses TextureView and disables mpv guide preview`() {
        assertEquals(
            LiveGuideSurfacePolicy.Decision(useTextureView = true, allowMpvPreview = false),
            LiveGuideSurfacePolicy.evaluate(
                LiveGuideSurfacePolicy.Device(
                    manufacturer = "Amazon",
                    model = "AFTKM",
                    device = "karat",
                    hardware = "mt8696",
                    board = "mt8696",
                )
            )
        )
    }

    @Test
    fun `onn Amlogic keeps preferred Media3 SurfaceView but disables embedded mpv preview`() {
        assertEquals(
            LiveGuideSurfacePolicy.Decision(useTextureView = false, allowMpvPreview = false),
            LiveGuideSurfacePolicy.evaluate(
                LiveGuideSurfacePolicy.Device(
                    manufacturer = "onn.",
                    model = "4K Streaming Box",
                    device = "onn_4k_gtv",
                    hardware = "amlogic",
                    board = "s905y4",
                )
            )
        )
    }

    @Test
    fun `Amazon alone does not activate TextureView but embedded mpv stays disabled`() {
        assertEquals(
            LiveGuideSurfacePolicy.Decision(useTextureView = false, allowMpvPreview = false),
            LiveGuideSurfacePolicy.evaluate(
                LiveGuideSurfacePolicy.Device(
                    manufacturer = "Amazon",
                    model = "future-model",
                    device = "future-device",
                    hardware = "future-soc",
                    board = "future-board",
                )
            )
        )
    }

    @Test
    fun `MT8696 on a non-Amazon device keeps SurfaceView but embedded mpv stays disabled`() {
        assertEquals(
            LiveGuideSurfacePolicy.Decision(useTextureView = false, allowMpvPreview = false),
            LiveGuideSurfacePolicy.evaluate(
                LiveGuideSurfacePolicy.Device(
                    manufacturer = "Other",
                    model = "AFTKM",
                    device = "karat",
                    hardware = "mt8696",
                    board = "mt8696",
                )
            )
        )
    }
}
