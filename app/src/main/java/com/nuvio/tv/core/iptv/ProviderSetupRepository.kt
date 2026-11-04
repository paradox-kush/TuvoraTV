package com.nuvio.tv.core.iptv

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.network.SYNC_BACKEND_HOSTED_ID
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.domain.model.AuthState
import com.posthog.PostHog
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** What the "Enter setup code" flow ends with. */
sealed interface RedeemFlowResult {
    /** Redeemed; the playlist is in the active profile and its key is known. */
    data class Added(val providerName: String?, val playlistKey: String?, val inActiveProfile: Boolean, val result: RedeemResult) : RedeemFlowResult
    data class Failed(val outcome: SetupCodeOutcome) : RedeemFlowResult
    /** The redeem succeeded (the code is spent) but added nothing: see [RedeemResultPolicy]. */
    data class NothingAdded(val kind: RedeemResultPolicy.Kind) : RedeemFlowResult
}

/** Step 2 — preview / redeem / detach, with the ordering the contract fixes. */
@Singleton
class ProviderSetupRepository @Inject constructor(
    private val api: ProviderSetupApi,
    private val refresher: ManagedInfoRefresher,
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val syncService: XtreamAccountSyncService,
    private val holder: SetupCodeHolder,
    private val backends: SyncBackendSupabaseProvider,
    private val claims: DeviceClaimStore,
    private val localPlaylists: DeviceSetupPlaylists,
) {
    /** The token of a device redeem whose answer never arrived, reused on retry (the server answers `already_redeemed`). */
    private var inFlightDevice: Pair<String, String>? = null

    /** True for a real (non-anonymous) account. TV's `FullAccount` is exactly that. */
    val isSignedIn: Boolean get() = authManager.authState.value is AuthState.FullAccount

    /** The email/user label the code screen shows ("Adding to <account>"). */
    val accountLabel: String? get() = (authManager.authState.value as? AuthState.FullAccount)?.email?.takeIf { it.isNotBlank() }

    fun infosFlow(profileId: Int): Flow<Map<String, ManagedPlaylistInfo>> = refresher.infosFlow(profileId)

    /** Validates on the device first (no request, no strike for a malformed code), then previews. */
    suspend fun preview(typed: String): SetupCodeOutcome {
        val code = when (val n = SetupCode.normalize(typed)) {
            is SetupCode.Normalized.Invalid -> return SetupCodeOutcome.forProblem(n.problem).also { capturePreview(it) }
            is SetupCode.Normalized.Valid -> n.value
        }
        // B205: the preview is public. A signed-out viewer previews like anyone; the code is held in memory so a
        // sign-in right after still finds it.
        if (!isSignedIn) holder.set(code)
        // The access token goes only to the hosted backend's own web host (security L4).
        val token = if (isSignedIn && ProviderSetupConfig.sendsToken(backends.selectedBackend.id == SYNC_BACKEND_HOSTED_ID, ProviderSetupConfig.BASE_URL, BuildConfig.IS_DEBUG_BUILD)) {
            runCatching { authManager.currentAccessToken() }.getOrNull()
        } else null
        return api.preview(code, token).also { capturePreview(it) }
    }

    /**
     * redeem -> (ONE playlist pull for the active profile) -> clear the in-memory code. The pull is
     * skipped for another profile: TV syncs the active profile only, and the other one is pulled when it
     * is switched to.
     */
    suspend fun redeem(typed: String, profileIndex: Int): RedeemFlowResult {
        val code = SetupCode.parse(typed) ?: return RedeemFlowResult.Failed(SetupCodeOutcome.Problem(SetupCode.Problem.WRONG_LENGTH))
        // Not a real account (signed out, or still loading): spend the code to this device, claim it on sign-in.
        if (!isSignedIn) return redeemToDevice(code)
        return when (val r = api.redeem(code, profileIndex, RedeemAddonsPolicy.skipAddons(AppFeaturePolicy.addonsEnabled))) {
            is RedeemOutcome.Failed -> {
                captureRedeem(0, 0, r.outcome)
                RedeemFlowResult.Failed(r.outcome)
            }
            is RedeemOutcome.Done -> {
                // The code is spent now, whatever the result says.
                holder.clear()
                val kind = RedeemResultPolicy.classify(r.result)
                val inActive = profileManager.activeProfileId.value == profileIndex
                // Same as Mobile: even "already in your account" pulls once, so a playlist this device has not
                // fetched yet shows up.
                if (inActive && kind in PULLS) runCatching { syncService.pullAndApply() }
                captureRedeem(r.result.added, r.result.updated, null)
                if (kind != RedeemResultPolicy.Kind.ADDED) return RedeemFlowResult.NothingAdded(kind)
                val infos = if (inActive) refresher.infosNow(profileIndex) else emptyMap()
                val key = r.result.playlistKeys.firstOrNull()
                RedeemFlowResult.Added(infos[key]?.providerName, key, inActive, r.result)
            }
        }
    }

    /**
     * B205: device redeem -> save the pending claim FIRST (it is the only handle on the spent code) -> add the
     * playlists to the local store. The claim token is secure-random; a network failure keeps it for the retry.
     */
    private suspend fun redeemToDevice(code: String): RedeemFlowResult {
        val token = inFlightDevice?.takeIf { it.first == code }?.second
            ?: DeviceClaimToken.newToken().also { inFlightDevice = code to it }
        return when (val r = api.deviceRedeem(code, DeviceClaimToken.hash(token))) {
            is DeviceRedeemOutcome.Failed -> {
                if (r.outcome != SetupCodeOutcome.Network) inFlightDevice = null
                captureRedeem(0, 0, r.outcome)
                RedeemFlowResult.Failed(r.outcome)
            }
            is DeviceRedeemOutcome.Done -> {
                inFlightDevice = null
                holder.clear()
                val added = r.redeem.playlists.filter { it.isAdded }
                claims.save(DeviceClaim(token, r.redeem.provider, System.currentTimeMillis(), added))
                runCatching { localPlaylists.addMissing(added) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                val result = RedeemResult(
                    status = r.redeem.status, profileIndex = profileManager.activeProfileId.value,
                    added = added.size, updated = 0, unchanged = 0,
                    playlistKeys = added.map { it.playlistKey },
                    playlistNames = added.mapNotNull { it.name },
                    skippedReasons = r.redeem.playlists.filterNot { it.isAdded }.map { it.reason ?: "missing_login" },
                )
                val kind = RedeemResultPolicy.classify(result)
                captureRedeem(result.added, 0, null)
                if (kind != RedeemResultPolicy.Kind.ADDED) return RedeemFlowResult.NothingAdded(kind)
                RedeemFlowResult.Added(r.redeem.provider, result.playlistKeys.firstOrNull(), true, result)
            }
        }
    }

    /** `detach_managed_playlist`, then refresh the managed map and force ONE pull. */
    suspend fun detach(profileId: Int, playlistKey: String): Boolean {
        val detached = api.detach(profileId, playlistKey).getOrNull() ?: return false
        refresher.refreshNow(profileId)
        if (profileManager.activeProfileId.value == profileId) runCatching { syncService.pullAndApply() }
        runCatching { PostHog.capture(event = "playlist_detached", properties = emptyMap()) }
        return detached
    }

    private companion object {
        val PULLS = setOf(RedeemResultPolicy.Kind.ADDED, RedeemResultPolicy.Kind.ALREADY_SET_UP)
    }

    fun clearCode() = holder.clear()
    fun heldCode(): String? = holder.get()

    // Outcome only. Never the code, never a URL or provider host.
    private fun capturePreview(o: SetupCodeOutcome) {
        runCatching { PostHog.capture(event = "setup_code_preview", properties = mapOf("outcome" to outcomeName(o))) }
    }

    private fun captureRedeem(added: Int, updated: Int, failure: SetupCodeOutcome?) {
        runCatching {
            PostHog.capture(
                event = "setup_code_redeemed",
                properties = mapOf("added" to added, "updated" to updated, "outcome" to (failure?.let(::outcomeName) ?: "done")),
            )
        }
    }

    private fun outcomeName(o: SetupCodeOutcome): String = when (o) {
        is SetupCodeOutcome.Ready -> "ready"
        SetupCodeOutcome.NeedsSignIn -> "needs_sign_in"
        is SetupCodeOutcome.Expired -> "expired"
        is SetupCodeOutcome.RateLimited -> "rate_limited"
        SetupCodeOutcome.Network -> "network"
        SetupCodeOutcome.Unusable -> "unusable"
        SetupCodeOutcome.ProfileNotFound -> "profile_not_found"
        is SetupCodeOutcome.Problem -> "problem"
    }
}
