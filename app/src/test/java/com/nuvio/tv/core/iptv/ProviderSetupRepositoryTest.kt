package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.network.SyncBackendConfig
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.domain.model.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The ordering and the data-minimising choices around preview and redeem. */
class ProviderSetupRepositoryTest {
    private val api = mockk<ProviderSetupApi>()
    private val sync = mockk<XtreamAccountSyncService>(relaxed = true)
    private val auth = mockk<AuthManager> {
        every { authState } returns MutableStateFlow<AuthState>(AuthState.FullAccount("u1", "me@example.com"))
        every { currentAccessToken() } returns "tok"
    }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    private val claims = mockk<DeviceClaimStore>(relaxed = true)
    private val local = mockk<DeviceSetupPlaylists>(relaxed = true)

    private fun repo(hosted: Boolean = true): ProviderSetupRepository {
        val backends = mockk<SyncBackendSupabaseProvider> {
            every { selectedBackend } returns mockk<SyncBackendConfig> { every { id } returns if (hosted) "hosted" else "nuvio" }
        }
        return ProviderSetupRepository(api, mockk(relaxed = true), auth, profiles, sync, SetupCodeHolder(), backends, claims, local)
    }

    private fun done(status: String = "redeemed", added: Int = 0, updated: Int = 0, unchanged: Int = 0, keys: List<String> = emptyList(), skipped: List<String> = emptyList()) =
        RedeemOutcome.Done(RedeemResult(status, 1, added, updated, unchanged, keys, emptyList(), skipped))

    @Test
    fun `the access token goes to the preview only for the hosted backend`() = runTest {
        coEvery { api.preview(any(), any()) } returns SetupCodeOutcome.Unusable
        repo(hosted = true).preview("ABCDEFGHJKMN")
        coVerify { api.preview("ABCDEFGHJKMN", "tok") }
        repo(hosted = false).preview("ABCDEFGHJKMN")
        coVerify { api.preview("ABCDEFGHJKMN", null) }
    }

    @Test
    fun `a signed-out viewer still gets the public preview and the code is held for sign-in`() = runTest {
        every { auth.authState } returns MutableStateFlow<AuthState>(AuthState.SignedOut)
        coEvery { api.preview(any(), any()) } returns SetupCodeOutcome.Unusable
        val holder = SetupCodeHolder()
        val backends = mockk<SyncBackendSupabaseProvider> {
            every { selectedBackend } returns mockk<SyncBackendConfig> { every { id } returns "hosted" }
        }
        ProviderSetupRepository(api, mockk(relaxed = true), auth, profiles, sync, holder, backends, claims, local).preview("ABCDEFGHJKMN")
        coVerify { api.preview("ABCDEFGHJKMN", null) }
        assertEquals("ABCDEFGHJKMN", holder.get())
    }

    private fun signedOutRepo(holder: SetupCodeHolder = SetupCodeHolder()): ProviderSetupRepository {
        every { auth.authState } returns MutableStateFlow<AuthState>(AuthState.SignedOut)
        val backends = mockk<SyncBackendSupabaseProvider> {
            every { selectedBackend } returns mockk<SyncBackendConfig> { every { id } returns "hosted" }
        }
        return ProviderSetupRepository(api, mockk(relaxed = true), auth, profiles, sync, holder, backends, claims, local)
    }

    private fun deviceDone(status: String = "redeemed", playlists: List<DevicePlaylist> = listOf(DevicePlaylist(playlistKey = "k1", name = "Live"))) =
        DeviceRedeemOutcome.Done(DeviceRedeem(status, "Acme TV", false, playlists))

    @Test
    fun `signed out the redeem goes to the device route with a 64-hex hash and never touches the account route`() = runTest {
        val hash = slot<String>()
        coEvery { api.deviceRedeem("ABCDEFGHJKMN", capture(hash)) } returns deviceDone()
        val r = signedOutRepo().redeem("ABCDEFGHJKMN", 1)
        assertEquals("k1", (r as RedeemFlowResult.Added).playlistKey)
        assertEquals("Acme TV", r.providerName)
        assertEquals(true, Regex("^[0-9a-f]{64}$").matches(hash.captured))
        coVerify(exactly = 0) { api.redeem(any(), any(), any()) }
        coVerify(exactly = 0) { sync.pullAndApply() }
    }

    @Test
    fun `signed out the playlists are stored locally and a pending claim keeps the token`() = runTest {
        val hash = slot<String>()
        val saved = slot<DeviceClaim>()
        coEvery { api.deviceRedeem(any(), capture(hash)) } returns deviceDone(
            playlists = listOf(DevicePlaylist(playlistKey = "k1"), DevicePlaylist(playlistKey = "k2", action = "skipped", reason = "missing_login")),
        )
        signedOutRepo().redeem("ABCDEFGHJKMN", 1)
        verify { claims.save(capture(saved)) }
        assertEquals("the claim token hashes to what the server was sent", hash.captured, DeviceClaimToken.hash(saved.captured.token))
        assertEquals("Acme TV", saved.captured.providerName)
        assertEquals(listOf("k1"), saved.captured.playlists.map { it.playlistKey })
        coVerify { local.addMissing(saved.captured.playlists) }
    }

    @Test
    fun `a network failure retries with the SAME token`() = runTest {
        val hashes = mutableListOf<String>()
        coEvery { api.deviceRedeem(any(), capture(hashes)) } returnsMany listOf(
            DeviceRedeemOutcome.Failed(SetupCodeOutcome.Network), deviceDone(status = "already_redeemed"),
        )
        val repo = signedOutRepo()
        assertEquals(RedeemFlowResult.Failed(SetupCodeOutcome.Network), repo.redeem("ABCDEFGHJKMN", 1))
        assertEquals(RedeemFlowResult.Added::class, repo.redeem("ABCDEFGHJKMN", 1)::class)
        assertEquals(2, hashes.size)
        assertEquals(hashes[0], hashes[1])
    }

    @Test
    fun `a refused code does not keep its token for the next code`() = runTest {
        val hashes = mutableListOf<String>()
        coEvery { api.deviceRedeem(any(), capture(hashes)) } returnsMany listOf(
            DeviceRedeemOutcome.Failed(SetupCodeOutcome.Unusable), DeviceRedeemOutcome.Failed(SetupCodeOutcome.Unusable),
        )
        val repo = signedOutRepo()
        repo.redeem("ABCDEFGHJKMN", 1)
        repo.redeem("ABCDEFGHJKMN", 1)
        assertNotEquals(hashes[0], hashes[1])
        verify(exactly = 0) { claims.save(any()) }
    }

    @Test
    fun `a device redeem that skipped everything stores no playlist and says why`() = runTest {
        coEvery { api.deviceRedeem(any(), any()) } returns deviceDone(playlists = listOf(DevicePlaylist(playlistKey = "k", action = "skipped", reason = "missing_login")))
        val r = signedOutRepo().redeem("ABCDEFGHJKMN", 1)
        assertEquals(RedeemFlowResult.NothingAdded(RedeemResultPolicy.Kind.NOTHING_NO_LOGIN), r)
    }

    @Test
    fun `a signed-in redeem still uses the account route`() = runTest {
        coEvery { api.redeem(any(), any(), any()) } returns done(added = 1, keys = listOf("k"))
        repo().redeem("ABCDEFGHJKMN", 1)
        coVerify(exactly = 0) { api.deviceRedeem(any(), any()) }
    }

    @Test
    fun `a malformed code never reaches the preview route`() = runTest {
        repo().preview("ABCD")
        coVerify(exactly = 0) { api.preview(any(), any()) }
    }

    @Test
    fun `redeem tells the backend to skip add-ons only when this build hides them`() = runTest {
        // Runs under both flavours: full shows add-ons (skip=false), playstore hides them (skip=true).
        coEvery { api.redeem(any(), any(), any()) } returns done(added = 1, keys = listOf("k"))
        repo().redeem("ABCDEFGHJKMN", 1)
        coVerify { api.redeem("ABCDEFGHJKMN", 1, !AppFeaturePolicy.addonsEnabled) }
    }

    @Test
    fun `an already-redeemed code still pulls once`() = runTest {
        coEvery { api.redeem(any(), any(), any()) } returns done(status = "already_redeemed")
        val r = repo().redeem("ABCDEFGHJKMN", 1)
        assertEquals(RedeemFlowResult.NothingAdded(RedeemResultPolicy.Kind.ALREADY_SET_UP), r)
        coVerify(exactly = 1) { sync.pullAndApply() }
    }

    @Test
    fun `a second code of a package whose playlist is already linked reads as added and pulls`() = runTest {
        coEvery { api.redeem(any(), any(), any()) } returns done(unchanged = 1, keys = listOf("k1"))
        val r = repo().redeem("ABCDEFGHJKMN", 1)
        assertEquals("k1", (r as RedeemFlowResult.Added).playlistKey)
        coVerify(exactly = 1) { sync.pullAndApply() }
    }

    @Test
    fun `a redeem that skipped everything pulls nothing and says why`() = runTest {
        coEvery { api.redeem(any(), any(), any()) } returns done(skipped = listOf("missing_login"))
        val r = repo().redeem("ABCDEFGHJKMN", 1)
        assertEquals(RedeemFlowResult.NothingAdded(RedeemResultPolicy.Kind.NOTHING_NO_LOGIN), r)
        coVerify(exactly = 0) { sync.pullAndApply() }
    }

    @Test
    fun `a failed redeem is passed through untouched`() = runTest {
        coEvery { api.redeem(any(), any(), any()) } returns RedeemOutcome.Failed(SetupCodeOutcome.Network)
        assertEquals(RedeemFlowResult.Failed(SetupCodeOutcome.Network), repo().redeem("ABCDEFGHJKMN", 1))
    }
}
