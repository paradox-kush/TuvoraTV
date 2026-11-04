package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceClaimPolicyTest {
    private fun d(o: ClaimOutcome) = DeviceClaimPolicy.decide(o)

    @Test
    fun `a claimed setup drops the claim and pulls once`() {
        assertEquals(DeviceClaimPolicy.Action.DROP_AND_PULL, d(ClaimOutcome.Claimed))
    }

    @Test
    fun `a setup that can never be claimed is restored locally and dropped`() {
        for (e in listOf(ClaimError.NOT_FOUND, ClaimError.ALREADY_USED, ClaimError.REVOKED, ClaimError.SUSPENDED, ClaimError.UNAVAILABLE)) {
            assertEquals(e.name, DeviceClaimPolicy.Action.RESTORE_AND_DROP, d(ClaimOutcome.Failed(e)))
        }
    }

    @Test
    fun `a transient failure keeps the claim for the next sign-in or app start`() {
        for (e in listOf(ClaimError.RATE_LIMITED, ClaimError.NETWORK, ClaimError.PROFILE_NOT_FOUND, ClaimError.UNKNOWN)) {
            assertEquals(e.name, DeviceClaimPolicy.Action.KEEP, d(ClaimOutcome.Failed(e)))
        }
    }

    @Test
    fun `restoring adds only added playlists whose key is missing`() {
        fun p(key: String, action: String = "added") = DevicePlaylist(playlistKey = key, action = action)
        val claim = DeviceClaim("t", "Acme", 1L, listOf(p("a"), p("b"), p("c", "skipped")))
        assertEquals(listOf("b"), DeviceClaimPolicy.missing(claim, setOf("a")).map { it.playlistKey })
        assertEquals(emptyList<String>(), DeviceClaimPolicy.missing(claim, setOf("a", "b")).map { it.playlistKey })
    }
}
