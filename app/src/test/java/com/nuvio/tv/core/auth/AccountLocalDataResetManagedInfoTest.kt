package com.nuvio.tv.core.auth

import com.nuvio.tv.core.iptv.ManagedInfoStore
import com.nuvio.tv.core.iptv.ManagedPlaylistInfo
import com.nuvio.tv.core.iptv.ProviderSupport
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security M4: signing out wipes the cached provider names and contact handles. */
class AccountLocalDataResetManagedInfoTest {
    private class Mem : ManagedInfoStore.Persistence {
        var v: String? = null
        override fun read() = v
        override fun write(value: String) { v = value }
    }

    @Test
    fun `sign-out clears the managed playlist cache`() = runTest {
        val disk = Mem()
        val store = ManagedInfoStore(disk)
        store.replace("u1", 1, listOf(ManagedPlaylistInfo("k", "Acme", null, ProviderSupport(email = "a@b.co"))), 1)
        assertNotNull(store.entry("u1", 1))
        val service = AccountLocalDataResetService(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            setOf<ProfileScopedCredentialStore>(store), mockk(relaxed = true), mockk(relaxed = true),
        )
        service.clearAfterSignOut()
        assertTrue(store.entries.value.isEmpty())
        assertTrue("and it is gone from disk", ManagedInfoStore(disk).entries.value.isEmpty())
    }
}
