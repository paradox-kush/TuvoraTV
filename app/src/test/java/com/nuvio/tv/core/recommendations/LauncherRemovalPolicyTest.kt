package com.nuvio.tv.core.recommendations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit argument order: assertEquals(message, expected, actual). */
class LauncherRemovalPolicyTest {

    @Test
    fun `an item never removed may publish`() {
        assertTrue("never removed", LauncherRemovalPolicy.mayPublish("wn_tt1", null, 1_000L))
    }

    @Test
    fun `a removed item stays hidden while it has not been played since`() {
        assertFalse("engaged before removal", LauncherRemovalPolicy.mayPublish("wn_tt1", 5_000L, 4_000L))
        assertFalse("engaged at the same instant", LauncherRemovalPolicy.mayPublish("wn_tt1", 5_000L, 5_000L))
    }

    @Test
    fun `a removed item comes back once it is played again in the app`() {
        assertTrue("replayed after removal", LauncherRemovalPolicy.mayPublish("wn_tt1", 5_000L, 6_000L))
    }

    @Test
    fun `an item with no identity never publishes`() {
        assertFalse("blank id", LauncherRemovalPolicy.mayPublish(" ", null, 1L))
    }

    @Test
    fun `a later episode of a removed show is a different item and may publish`() {
        val removed = LauncherRemovalPolicy.recordRemoval(emptyMap(), "wn_tt1_s1e2", 5_000L)
        assertFalse("same episode", LauncherRemovalPolicy.mayPublish("wn_tt1_s1e2", removed, 4_000L))
        assertTrue("next episode", LauncherRemovalPolicy.mayPublish("wn_tt1_s1e3", removed, 4_000L))
    }

    @Test
    fun `removing from either surface hides the item on both`() {
        val removedFromWatchNext = LauncherRemovalPolicy.recordRemoval(emptyMap(), "wn_tt1_s1e2", 5_000L)
        assertFalse("preview channel id", LauncherRemovalPolicy.mayPublish("tt1_s1e2", removedFromWatchNext, 4_000L))
        val removedFromChannel = LauncherRemovalPolicy.recordRemoval(emptyMap(), "tt1_s1e2", 5_000L)
        assertFalse("watch next id", LauncherRemovalPolicy.mayPublish("wn_tt1_s1e2", removedFromChannel, 4_000L))
    }

    @Test
    fun `removing again keeps the latest time`() {
        val first = LauncherRemovalPolicy.recordRemoval(emptyMap(), "wn_tt1", 5_000L)
        val second = LauncherRemovalPolicy.recordRemoval(first, "wn_tt1", 9_000L)
        assertEquals("latest removal", mapOf("tt1" to 9_000L), second)
        val older = LauncherRemovalPolicy.recordRemoval(second, "wn_tt1", 2_000L)
        assertEquals("an older timestamp never rewinds it", mapOf("tt1" to 9_000L), older)
    }

    @Test
    fun `encode and decode round-trip including ids with separators`() {
        val removed = mapOf("tt1_s1e2" to 5_000L, "kitsu:12|odd" to 7_000L)
        assertEquals("round trip", removed, LauncherRemovalPolicy.decode(LauncherRemovalPolicy.encode(removed)))
    }

    @Test
    fun `decode skips malformed entries`() {
        val decoded = LauncherRemovalPolicy.decode(setOf("garbage", "|x", "12|", "abc|tt1", "40|tt2"))
        assertEquals("only the valid entry", mapOf("tt2" to 40L), decoded)
    }

    @Test
    fun `the remembered list is capped and forgets the oldest first`() {
        var removed = emptyMap<String, Long>()
        for (i in 1..520) removed = LauncherRemovalPolicy.recordRemoval(removed, "wn_tt$i", i.toLong())
        assertEquals("capped", 500, removed.size)
        assertFalse("oldest forgotten", removed.containsKey("tt1"))
        assertTrue("newest kept", removed.containsKey("tt520"))
    }
}
