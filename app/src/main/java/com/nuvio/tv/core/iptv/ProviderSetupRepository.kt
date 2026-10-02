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
) {
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
        if (!isSignedIn) {
            holder.set(code)
            return SetupCodeOutcome.NeedsSignIn.also { capturePreview(it) }
        }
        // The access token goes only to the hosted backend's own web host (security L4).
        val token = if (ProviderSetupConfig.sendsToken(backends.selectedBackend.id == SYNC_BACKEND_HOSTED_ID, ProviderSetupConfig.BASE_URL, BuildConfig.IS_DEBUG_BUILD)) {
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
