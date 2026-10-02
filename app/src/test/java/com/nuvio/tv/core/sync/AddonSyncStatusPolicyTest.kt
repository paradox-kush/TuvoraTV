package com.nuvio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonSyncStatusPolicyTest {
    private val a = "https://a.example"
    private val b = "https://b.example"

    private fun status(
        local: List<String>,
        lastSynced: List<String>?,
        hasAccount: Boolean = true,
        followsPrimaryProfile: Boolean = false,
        syncInFlight: Boolean = false
    ) = AddonSyncStatusPolicy.status(local, lastSynced, hasAccount, followsPrimaryProfile, syncInFlight)

    @Test
    fun `list matching the last synced baseline is synced`() {
        assertEquals("same list", AddonSyncStatus.Synced, status(listOf(a, b), listOf(a, b)))
    }

    @Test
    fun `addon added after the last sync is not synced`() {
        assertEquals("unpushed add", AddonSyncStatus.NotSynced, status(listOf(a, b), listOf(a)))
    }

    @Test
    fun `addon removed after the last sync is not synced`() {
        assertEquals("unpushed removal", AddonSyncStatus.NotSynced, status(listOf(a), listOf(a, b)))
    }

    @Test
    fun `unpushed reorder is not synced`() {
        assertEquals("unpushed reorder", AddonSyncStatus.NotSynced, status(listOf(b, a), listOf(a, b)))
    }

    @Test
    fun `device that never synced with addons installed is not synced`() {
        assertEquals("never synced", AddonSyncStatus.NotSynced, status(listOf(a), null))
    }

    @Test
    fun `device that never synced with no addons has nothing to say`() {
        assertEquals("empty and never synced", AddonSyncStatus.Synced, status(emptyList(), null))
    }

    @Test
    fun `no account means no marker`() {
        assertEquals("signed out", AddonSyncStatus.NotApplicable, status(listOf(a), null, hasAccount = false))
    }

    @Test
    fun `profile using the primary addons gets no marker`() {
        assertEquals(
            "follows primary",
            AddonSyncStatus.NotApplicable,
            status(listOf(a), listOf(b), followsPrimaryProfile = true)
        )
    }

    @Test
    fun `push in flight hides the marker instead of flashing it`() {
        assertEquals("in flight", AddonSyncStatus.Syncing, status(listOf(a, b), listOf(a), syncInFlight = true))
    }

    @Test
    fun `same addon under another url spelling is synced`() {
        val result = AddonSyncStatusPolicy.status(
            local = listOf("https://A.example"),
            lastSynced = listOf(a),
            hasAccount = true,
            followsPrimaryProfile = false,
            syncInFlight = false,
            key = { it.lowercase() }
        )
        assertEquals("canonical key match", AddonSyncStatus.Synced, result)
    }
}
