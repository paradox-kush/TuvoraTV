package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** B68 — a mutation id is reused only for the exact payload it was minted for. */
class PlaylistMutationIdPolicyTest {

    private val ab = PlaylistMutationIdPolicy.fingerprint("[A,B]", deleteAll = false)

    @Test
    fun `the same payload keeps its id so a lost-response retry dedups`() {
        val again = PlaylistMutationIdPolicy.fingerprint("[A,B]", deleteAll = false)
        assertEquals("same payload reuses the id", "m-1", PlaylistMutationIdPolicy.idFor("m-1", ab, again) { "fresh" })
    }

    @Test
    fun `a different payload gets a fresh id`() {
        val abc = PlaylistMutationIdPolicy.fingerprint("[A,B,C]", deleteAll = false)
        assertEquals("changed payload mints", "fresh", PlaylistMutationIdPolicy.idFor("m-1", ab, abc) { "fresh" })
    }

    @Test
    fun `an id stored without its fingerprint is never reused`() {
        assertEquals("legacy id mints", "fresh", PlaylistMutationIdPolicy.idFor("m-1", null, ab) { "fresh" })
    }

    @Test
    fun `no stored id mints one`() {
        assertEquals("no id mints", "fresh", PlaylistMutationIdPolicy.idFor(null, null, ab) { "fresh" })
    }

    @Test
    fun `the fingerprint covers the delete-all flag`() {
        assertNotEquals("delete-all changes the fingerprint",
            PlaylistMutationIdPolicy.fingerprint("[]", deleteAll = false),
            PlaylistMutationIdPolicy.fingerprint("[]", deleteAll = true))
    }
}
