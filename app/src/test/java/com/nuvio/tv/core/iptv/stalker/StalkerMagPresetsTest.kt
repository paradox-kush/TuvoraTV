package com.nuvio.tv.core.iptv.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StalkerMagPresetsTest {

    /**
     * The identity Tuvora shipped before the ladder existed has to stay first, or every portal that
     * already works pays an extra rejected round-trip on its next login.
     */
    @Test
    fun `the ladder starts with the identity we always sent`() {
        assertEquals("generic_mag250", StalkerMagPresets.DEFAULT.id)
        assertEquals("MAG250", StalkerMagPresets.DEFAULT.stbType)
        assertEquals("218", StalkerMagPresets.DEFAULT.imageVersion)
        assertEquals("1.7-BD-00", StalkerMagPresets.DEFAULT.hwVersion)
    }

    @Test
    fun `nothing tried yet starts at the default`() {
        assertEquals(StalkerMagPresets.DEFAULT, StalkerMagPresets.next(null))
    }

    @Test
    fun `each rejection advances one rung`() {
        val first = StalkerMagPresets.DEFAULT
        val second = StalkerMagPresets.next(first)
        assertNotNull(second)
        assertEquals("mag254_strict", second!!.id)
        val third = StalkerMagPresets.next(second)
        assertNotNull(third)
        assertEquals("ministra_mag322", third!!.id)
    }

    /** Exhausted means the account is wrong, not the identity — stop rather than loop. */
    @Test
    fun `the ladder ends`() {
        assertNull(StalkerMagPresets.next(StalkerMagPresets.LADDER.last()))
    }

    /**
     * The winning preset id is remembered per account, so a build that drops or renames one can be
     * handed an id it no longer knows. Restart the ladder instead of stranding the account.
     */
    @Test
    fun `an unknown remembered preset restarts the ladder`() {
        val forgotten = StalkerMagPresets.DEFAULT.copy(id = "retired_preset")
        assertEquals(StalkerMagPresets.DEFAULT, StalkerMagPresets.next(forgotten))
    }

    @Test
    fun `presets are distinct in the fields portals fingerprint`() {
        val identities = StalkerMagPresets.LADDER.map {
            listOf(it.stbType, it.imageVersion, it.hwVersion, it.userAgent, it.xUserAgent)
        }
        assertEquals(
            "a duplicate identity would waste a rung",
            identities.size,
            identities.distinct().size
        )
        assertEquals(
            "preset ids must be unique — they are persisted",
            StalkerMagPresets.LADDER.size,
            StalkerMagPresets.LADDER.map { it.id }.distinct().size
        )
    }

    @Test
    fun `byId round-trips every rung and rejects the unknown`() {
        StalkerMagPresets.LADDER.forEach { preset ->
            assertEquals(preset, StalkerMagPresets.byId(preset.id))
        }
        assertNull(StalkerMagPresets.byId("nope"))
        assertNull(StalkerMagPresets.byId(null))
    }
}
