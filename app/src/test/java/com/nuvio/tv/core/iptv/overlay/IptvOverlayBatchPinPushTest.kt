package com.nuvio.tv.core.iptv.overlay

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * "Pin all N" is one overlay edit: every pin is written, then the device pushes ONCE. A push per pin is the
 * amplification the overlay sync has been bitten by before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class IptvOverlayBatchPinPushTest {
    private val db = IptvOverlayDb(RuntimeEnvironment.getApplication())
    private val postgrest = mockk<Postgrest>(relaxed = true)
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    private val auth = mockk<AuthManager> { every { canSync } returns true }
    private val repo = IptvOverlayRepository(db, postgrest, profiles, auth)

    @Test
    fun `pinning four channels writes four pins and pushes once`() {
        val targets = (1..4).map { "ent$it" to "pl" as String? }
        repo.setChannelsPinned(targets, pinned = true)

        coVerify(exactly = 1, timeout = 10_000) { postgrest.rpc("sync_push_iptv_overlay", any(), any()) }
        val pinned = db.snapshot(1).channels.filterValues { it.pinned }.keys
        assertEquals("all four are pinned", targets.map { it.first }.toSet(), pinned)
        // Let any stray second push surface before the final count.
        Thread.sleep(500)
        coVerify(exactly = 1) { postgrest.rpc("sync_push_iptv_overlay", any(), any()) }
    }
}
