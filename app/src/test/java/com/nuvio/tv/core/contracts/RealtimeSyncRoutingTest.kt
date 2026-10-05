package com.nuvio.tv.core.contracts

import com.nuvio.tv.core.iptv.overlay.IptvOverlayRealtimeParticipant
import com.nuvio.tv.core.iptv.overlay.IptvOverlayRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B115: a website overlay edit emits a `sync_invalidations` row with surface `iptv_overlay`. TV's
 * StartupSyncService logged it as "Unknown realtime sync surface" and dropped it, so the edit only landed
 * when the guide was reopened. The surface now routes to the participant that declares it.
 */
class RealtimeSyncRoutingTest {

    private class Fake(override val name: String, override val realtimeSurfaces: Set<String>) : RealtimeSyncParticipant {
        override suspend fun pullForRealtimeSurface(profileId: Int) = Unit
    }

    @Test
    fun `a fork surface routes only to the participants that declare it`() {
        val overlay = Fake("overlay", setOf("iptv_overlay"))
        val other = Fake("other", setOf("radar"))
        val routed = RealtimeSyncRouting.participantsFor("iptv_overlay", listOf(other, overlay))
        assertEquals("only the overlay participant", listOf("overlay"), routed.map { it.name })
    }

    @Test
    fun `an unknown surface routes nowhere`() {
        assertTrue(RealtimeSyncRouting.participantsFor("nope", listOf(Fake("overlay", setOf("iptv_overlay")))).isEmpty())
    }

    @Test
    fun `overlay participant declares iptv_overlay and pulls that profile`() = runBlocking {
        val repo = mockk<IptvOverlayRepository>()
        coEvery { repo.pullForProfile(any()) } returns true
        val participant = IptvOverlayRealtimeParticipant(repo)
        assertTrue(participant.realtimeSurfaces.contains("iptv_overlay"))
        participant.pullForRealtimeSurface(3)
        coVerify(exactly = 1) { repo.pullForProfile(3) }
    }
}
