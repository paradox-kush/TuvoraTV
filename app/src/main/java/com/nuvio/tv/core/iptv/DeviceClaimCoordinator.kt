package com.nuvio.tv.core.iptv

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.domain.model.AuthState
import com.posthog.PostHog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B205 — moves device-redeemed setups into the account once there is one. Event-driven: it runs when auth becomes a
 * full account (which is also app start when already signed in), single-flight, with no timer. A claim the server
 * could not take is kept for the next sign-in or app start; see [DeviceClaimPolicy].
 */
@Singleton
class DeviceClaimCoordinator @Inject constructor(
    private val api: ProviderSetupApi,
    private val claims: DeviceClaimStore,
    private val localPlaylists: DeviceSetupPlaylists,
    private val profileManager: ProfileManager,
    private val syncService: XtreamAccountSyncService,
    private val authManager: AuthManager,
) {
    private val mutex = Mutex()
    private var started = false

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            authManager.authState.filterIsInstance<AuthState.FullAccount>().distinctUntilChanged().collect { claimPending() }
        }
    }

    /** One pass over the pending claims. Safe to call twice: the second waits for the first. */
    suspend fun claimPending() = mutex.withLock {
        if (authManager.authState.value !is AuthState.FullAccount) return@withLock
        var pull = false
        var restored = false
        for (claim in claims.all()) {
            val outcome = api.claimDeviceSetup(
                claim.token, profileManager.activeProfileId.value, RedeemAddonsPolicy.skipAddons(AppFeaturePolicy.addonsEnabled),
            )
            val action = DeviceClaimPolicy.decide(outcome)
            when (action) {
                DeviceClaimPolicy.Action.DROP_AND_PULL -> { claims.remove(claim.token); pull = true }
                DeviceClaimPolicy.Action.RESTORE_AND_DROP -> {
                    // addMissing is idempotent; drop the claim only once the playlists are safely local.
                    val ok = runCatching { localPlaylists.addMissing(claim.playlists) }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                        .isSuccess
                    if (ok) { claims.remove(claim.token); restored = true }
                }
                DeviceClaimPolicy.Action.KEEP -> Unit
            }
            // Outcome only: never the token, the code, a host or a playlist.
            runCatching { PostHog.capture(event = "setup_code_claim", properties = mapOf("action" to action.name.lowercase())) }
        }
        if (pull) runCatching { syncService.pullAndApply() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else Log.w(TAG, "claim pull failed: ${it.javaClass.simpleName}") }
        if (restored) syncService.triggerRemoteSync()
    }

    private companion object { const val TAG = "DeviceClaimCoordinator" }
}
