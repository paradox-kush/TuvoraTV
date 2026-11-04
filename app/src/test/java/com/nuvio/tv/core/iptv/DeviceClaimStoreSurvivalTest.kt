package com.nuvio.tv.core.iptv

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nuvio.tv.core.auth.AccountLocalDataResetService
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** B205: the pending claim is the only handle on a spent code, so sign-out / account switch must not erase it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeviceClaimStoreSurvivalTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val claim = DeviceClaim("a".repeat(64), "Acme", 5L, listOf(DevicePlaylist(playlistKey = "k1", name = "Live", username = "u", password = "p")))

    @Test
    fun `the store round-trips a claim and removes it by token`() {
        val store = DeviceClaimStore(context)
        store.save(claim)
        store.save(claim.copy(providerName = "Acme 2"))
        assertEquals("saving the same token replaces it", listOf(claim.copy(providerName = "Acme 2")), store.all())
        store.remove(claim.token)
        assertEquals(emptyList<DeviceClaim>(), store.all())
    }

    @Test
    fun `a claim survives the real sign-out wipe and a restart of the store`() = runTest {
        DeviceClaimStore(context).save(claim)
        // Real profile-scoped data wipe + real files wipe; the store is read back through a fresh instance.
        val factory = ProfileDataStoreFactory(context)
        File(context.filesDir, "datastore").mkdirs()
        File(context.filesDir, "datastore/xtream_accounts_p1.preferences_pb").writeText("x")
        val wipe = AccountLocalDataResetService(
            context, factory, mockk(relaxed = true), mockk(relaxed = true), emptySet(), mockk(relaxed = true), mockk(relaxed = true),
        )
        wipe.clearAfterSignOut()
        assertTrue("the wipe really ran", !File(context.filesDir, "datastore/xtream_accounts_p1.preferences_pb").exists())
        assertEquals(listOf(claim), DeviceClaimStore(context).all())
    }
}
