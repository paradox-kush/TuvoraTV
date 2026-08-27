package com.nuvio.tv.playback.android

import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.SurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPlaybackQuirkRegistryTest {
    @Test
    fun `verified Amazon AFTKM gets only the narrow guide TextureView override`() {
        val applied = AndroidPlaybackQuirkRegistry.resolve(
            device = fireDevice,
            codecStableIds = setOf("OMX.MTK.HEVC|video/hevc"),
            nowEpochMs = 1_800_000_000_000L,
            apiLevel = 35,
        ).single()

        assertEquals(AndroidPlaybackQuirkDomain.VIDEO_RENDERER_SURFACE, applied.quirk.domain)
        val override = applied.quirk.override as AndroidPlaybackQuirkOverride.ForceEmbeddedSurface
        assertEquals(SessionProfile.GUIDE, override.profile)
        assertEquals(SurfaceMode.TEXTURE_VIEW, override.surfaceMode)
        assertFalse(applied.revalidationDue)
        assertTrue(applied.quirk.evidenceReference.contains("livetv-freeze-rca-2026-08-26"))
    }

    @Test
    fun `manufacturer and model matching is exact and conjunctive`() {
        val wrongManufacturer = fireDevice.copy(manufacturer = "Other")
        val wrongModel = fireDevice.copy(model = "AFTKA")

        assertTrue(resolve(wrongManufacturer, 1_800_000_000_000L).isEmpty())
        assertTrue(resolve(wrongModel, 1_800_000_000_000L).isEmpty())
    }

    @Test
    fun `quirk becomes due for revalidation then expires`() {
        val due = resolve(fireDevice, 1_803_600_000_000L).single()
        val expired = resolve(fireDevice, 1_819_238_400_000L)

        assertTrue(due.revalidationDue)
        assertTrue(expired.isEmpty())
    }

    @Test
    fun `optional codec API and firmware constraints all match exactly`() {
        val match = AndroidPlaybackQuirkMatch(
            manufacturer = "Amazon",
            model = "AFTKM",
            codecStableId = "OMX.MTK.HEVC|video/hevc",
            apiLevel = 35,
            firmware = "PS7699/4007",
        )

        assertTrue(match.matches(fireDevice, setOf("OMX.MTK.HEVC|video/hevc"), runtimeApiLevel = 35))
        assertFalse(match.matches(fireDevice, setOf("OMX.MTK.AVC|video/avc"), runtimeApiLevel = 35))
        assertFalse(match.matches(fireDevice, setOf("OMX.MTK.HEVC|video/hevc"), runtimeApiLevel = 36))
        assertFalse(
            match.matches(
                fireDevice.copy(firmware = "different"),
                setOf("OMX.MTK.HEVC|video/hevc"),
                runtimeApiLevel = 35,
            ),
        )
    }

    private fun resolve(device: AndroidDeviceFacts, nowEpochMs: Long) =
        AndroidPlaybackQuirkRegistry.resolve(
            device = device,
            codecStableIds = emptySet(),
            nowEpochMs = nowEpochMs,
            apiLevel = 35,
        )

    private companion object {
        val fireDevice = AndroidDeviceFacts(
            manufacturer = "Amazon",
            model = "AFTKM",
            device = "karat",
            hardware = "mt8696",
            board = "mt8696",
            firmware = "PS7699/4007",
        )
    }
}
