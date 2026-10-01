package com.nuvio.tv.core.sync

import com.nuvio.tv.data.remote.supabase.SupabaseProviderCredential
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialModelsTest {
    @Test
    fun completeRemoteSnapshotDoesNotRequireSeeding() {
        val snapshot = ProviderCredentialSnapshot(
            profileId = 1,
            values = listOf(
                ProviderCredentialValue("debrid:torbox", "api_key", "local-torbox"),
                ProviderCredentialValue("animeskip", "client_id", "local-anime")
            )
        )
        val rows = listOf(
            SupabaseProviderCredential(
                provider = "DEBRID:TORBOX",
                credentialJson = buildJsonObject { put("api_key", "remote") }
            ),
            SupabaseProviderCredential(
                provider = "animeskip",
                credentialJson = buildJsonObject { put("client_id", "remote") }
            )
        )

        assertFalse(shouldSeedProviderCredentials(snapshot, rows))
    }

    @Test
    fun missingRemoteProviderRequiresSeeding() {
        val snapshot = ProviderCredentialSnapshot(
            profileId = 1,
            values = listOf(
                ProviderCredentialValue("debrid:torbox", "api_key", "local-torbox"),
                ProviderCredentialValue("animeskip", "client_id", "local-anime")
            )
        )
        val rows = listOf(
            SupabaseProviderCredential(
                provider = "debrid:torbox",
                credentialJson = buildJsonObject { put("api_key", "remote") }
            )
        )

        assertTrue(shouldSeedProviderCredentials(snapshot, rows))
    }

    @Test
    fun `remote values replace only supported local providers`() {
        val local = ProviderCredentialSnapshot(
            profileId = 2,
            values = listOf(
                ProviderCredentialValue("debrid:torbox", "api_key", "local-torbox"),
                ProviderCredentialValue("animeskip", "client_id", "local-anime")
            )
        )
        val remote = listOf(
            SupabaseProviderCredential(
                provider = "debrid:torbox",
                credentialJson = buildJsonObject { put("api_key", "remote-torbox") }
            ),
            SupabaseProviderCredential(
                provider = "tmdb",
                credentialJson = buildJsonObject { put("api_key", "remote-tmdb") }
            )
        )

        val merged = local.mergeRemote(remote)

        assertEquals("remote-torbox", merged.values[0].value)
        assertEquals("local-anime", merged.values[1].value)
    }

    @Test
    fun `blank remote value is retained as a clear tombstone`() {
        val local = ProviderCredentialSnapshot(
            profileId = 1,
            values = listOf(ProviderCredentialValue("mdblist", "api_key", "local"))
        )
        val remote = listOf(
            SupabaseProviderCredential(
                provider = "mdblist",
                credentialJson = buildJsonObject { put("api_key", "") }
            )
        )

        assertEquals("", local.mergeRemote(remote).values.single().value)
    }

    @Test
    fun `local edit pushes only the providers that changed`() {
        val baseline = ProviderCredentialSnapshot(
            profileId = 1,
            values = listOf(
                ProviderCredentialValue("debrid:torbox", "api_key", ""),
                ProviderCredentialValue("mdblist", "api_key", ""),
                ProviderCredentialValue("animeskip", "client_id", "")
            )
        )
        val edited = baseline.copy(
            values = listOf(
                ProviderCredentialValue("debrid:torbox", "api_key", "new-torbox"),
                ProviderCredentialValue("mdblist", "api_key", ""),
                ProviderCredentialValue("animeskip", "client_id", "")
            )
        )

        assertEquals(
            "a stale blank MDBList placeholder must not ride along with the TorBox edit",
            listOf("debrid:torbox"),
            edited.changedSince(baseline).values.map { it.provider }
        )
    }

    @Test
    fun `clearing a key still pushes its blank`() {
        val baseline = ProviderCredentialSnapshot(
            profileId = 1,
            values = listOf(
                ProviderCredentialValue("mdblist", "api_key", "key"),
                ProviderCredentialValue("animeskip", "client_id", "")
            )
        )
        val cleared = baseline.copy(
            values = listOf(
                ProviderCredentialValue("mdblist", "api_key", ""),
                ProviderCredentialValue("animeskip", "client_id", "")
            )
        )

        assertEquals(
            "an explicit clear is a real change",
            listOf(ProviderCredentialValue("mdblist", "api_key", "")),
            cleared.changedSince(baseline).values
        )
    }

    @Test
    fun `no baseline pushes every value`() {
        val snapshot = ProviderCredentialSnapshot(
            profileId = 3,
            values = listOf(ProviderCredentialValue("mdblist", "api_key", "key"))
        )

        assertEquals("first push carries the whole snapshot", snapshot, snapshot.changedSince(null))
    }

    @Test
    fun `unchanged snapshot pushes nothing`() {
        val snapshot = ProviderCredentialSnapshot(
            profileId = 1,
            values = listOf(ProviderCredentialValue("mdblist", "api_key", "key"))
        )

        assertTrue("nothing changed", snapshot.changedSince(snapshot).values.isEmpty())
    }
}
