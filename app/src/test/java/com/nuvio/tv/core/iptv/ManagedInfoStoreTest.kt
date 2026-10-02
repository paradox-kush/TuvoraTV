package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 contract section 5: the managed map is cached per profile, refreshed only after a pull with playlists. */
class ManagedInfoStoreTest {

    private class MemoryPersistence : ManagedInfoStore.Persistence {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    private val info = ManagedPlaylistInfo("k1", "Acme TV", "Main", ProviderSupport(telegram = "acme_tv"), "2026-10-01T10:00:00Z")

    @Test
    fun `entries are keyed by user and profile and survive a restart`() {
        val disk = MemoryPersistence()
        val store = ManagedInfoStore(disk)
        store.replace("u1", 1, listOf(info), revision = 7, nowMs = 1234)
        assertEquals(setOf("k1"), store.infos("u1", 1).keys)
        assertTrue("another profile sees nothing", store.infos("u1", 2).isEmpty())
        assertTrue("another user sees nothing", store.infos("u2", 1).isEmpty())
        val reloaded = ManagedInfoStore(disk)
        assertEquals(info, reloaded.infos("u1", 1)["k1"])
        assertEquals(7L, reloaded.entry("u1", 1)?.revision)
        assertEquals(1234L, reloaded.entry("u1", 1)?.fetchedAtMs)
    }

    @Test
    fun `clearAll forgets everything`() {
        val store = ManagedInfoStore(MemoryPersistence())
        store.replace("u1", 1, listOf(info), 1)
        store.clearAll()
        assertNull(store.entry("u1", 1))
    }

    @Test
    fun `corrupt persisted state is an empty cache not a crash`() {
        val disk = MemoryPersistence().apply { value = "{not json" }
        assertTrue(ManagedInfoStore(disk).entries.value.isEmpty())
    }

    // --- ManagedRefreshPolicy -------------------------------------------------------------------

    private fun should(
        pull: Boolean = true, count: Int = 2, rev: Long? = 5, cachedRev: Long? = 5, hasCache: Boolean = true, age: Long = 0,
    ) = ManagedRefreshPolicy.shouldRefresh(pull, count, rev, cachedRev, hasCache, age)

    @Test
    fun `no read for a profile with zero playlists or after a failed pull`() {
        assertFalse(should(count = 0, hasCache = false))
        assertFalse(should(pull = false, hasCache = false))
    }

    @Test
    fun `first read after a pull with playlists and no cache`() {
        assertTrue(should(hasCache = false, cachedRev = null))
    }

    @Test
    fun `an unchanged revision is not read again but a changed one is`() {
        assertFalse(should(rev = 5, cachedRev = 5))
        assertTrue(should(rev = 6, cachedRev = 5))
        assertFalse("the legacy pull has no revision: a fresh cache is not re-read on every pull", should(rev = null, cachedRev = 5))
        assertTrue("but an old one is", should(rev = null, cachedRev = 5, age = ManagedRefreshPolicy.MAX_AGE_MS))
    }

    @Test
    fun `a cache a day old is read again at the next pull`() {
        assertFalse(should(age = ManagedRefreshPolicy.MAX_AGE_MS - 1))
        assertTrue(should(age = ManagedRefreshPolicy.MAX_AGE_MS))
    }

    // --- account data: sign-out, profile delete, profile swap ----------------------------------

    @Test
    fun `it is part of the profile-scoped store set so sign-out wipes it`() {
        val store = ManagedInfoStore(MemoryPersistence().also { })
        val set: Set<com.nuvio.tv.core.profile.ProfileScopedCredentialStore> = setOf(store)
        store.replace("u1", 1, listOf(info), 1)
        store.replace("u2", 2, listOf(info), 1)
        set.forEach(com.nuvio.tv.core.profile.ProfileScopedCredentialStore::clearAllProfiles)
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun `a deleted profile's slot goes for every user, others stay`() {
        val disk = MemoryPersistence()
        val store = ManagedInfoStore(disk)
        store.replace("u1", 2, listOf(info), 1)
        store.replace("u2", 2, listOf(info), 1)
        store.replace("u1", 3, listOf(info), 1)
        store.removeProfile(2)
        assertNull(store.entry("u1", 2)); assertNull(store.entry("u2", 2))
        assertEquals(setOf("k1"), store.infos("u1", 3).keys)
        assertNull("and it is gone from disk", ManagedInfoStore(disk).entry("u1", 2))
    }

    @Test
    fun `promoting a profile swaps the cached maps with it`() {
        val store = ManagedInfoStore(MemoryPersistence())
        val other = info.copy(playlistKey = "k2")
        store.replace("u1", 1, listOf(info), 1)
        store.replace("u1", 2, listOf(other), 1)
        store.swapProfiles(1, 2)
        assertEquals(setOf("k2"), store.infos("u1", 1).keys)
        assertEquals(setOf("k1"), store.infos("u1", 2).keys)
    }
}
