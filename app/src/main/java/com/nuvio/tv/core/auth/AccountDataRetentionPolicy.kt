package com.nuvio.tv.core.auth

/**
 * Why an account session ended. Only a deliberate end erases this device's local data.
 *
 * KMP twin: com.nuvio.app.core.auth.AccountDataRetentionPolicy in NuvioMobile / NuvioDesktop
 * (same reasons, same decisions).
 */
enum class SessionEndReason {
    /** The viewer pressed Sign out. */
    USER_SIGN_OUT,

    /** The viewer deleted their account. */
    ACCOUNT_DELETED,

    /** The viewer switched sync server; the data belongs to the old server's account. */
    SYNC_BACKEND_SWITCH,

    /**
     * A different account signed in over a lost session's kept data and the viewer confirmed that the
     * previous account's data may leave the device (see [SignInDataDecision.ASK_BEFORE_REPLACING_OTHER_ACCOUNT_DATA]).
     */
    SWITCHED_ACCOUNT,

    /**
     * The session went away without the viewer asking: a rejected or failed token refresh, a 401
     * carrying an invalid-session marker, a stored session that is simply missing on the next
     * launch ("No entry with the key sb-<ref>-session"), a refresh race, a failed sync-code claim.
     * None of these say anything about the data on the device.
     */
    SESSION_LOST,
}

/** The account whose data is on this device, recorded while that account is signed in. */
data class LocalDataOwner(val userId: String, val email: String?)

/** A different account signed in while [previousOwner]'s kept data is still on the device. */
data class AccountSwitchPrompt(
    val previousOwner: LocalDataOwner,
    val newUserId: String,
    val newEmail: String?,
)

enum class SignInDataDecision {
    /** No other account's data is on the device (or it is this account's own): sync as usual. */
    PROCEED,

    /**
     * Another account's data, possibly with changes that never synced, is still on the device
     * because its session was lost rather than signed out. It must never be pushed into, or merged
     * with, the account that just signed in, and it must not be erased without the viewer saying so.
     */
    ASK_BEFORE_REPLACING_OTHER_ACCOUNT_DATA,
}

/**
 * D1 data-loss fix: a lost session used to run the same local wipe as the Sign out button
 * (AuthManager.handleUnexpectedSignedOut -> AccountLocalDataResetService.clearAfterSignOut), so every
 * profile's layout, library, Continue Watching and unsynced progress vanished on a failed refresh.
 */
object AccountDataRetentionPolicy {
    fun wipesLocalData(reason: SessionEndReason): Boolean = when (reason) {
        SessionEndReason.USER_SIGN_OUT,
        SessionEndReason.ACCOUNT_DELETED,
        SessionEndReason.SYNC_BACKEND_SWITCH,
        SessionEndReason.SWITCHED_ACCOUNT -> true
        SessionEndReason.SESSION_LOST -> false
    }

    fun decideOnSignIn(owner: LocalDataOwner?, signedInUserId: String): SignInDataDecision {
        val ownerId = owner?.userId?.takeIf { it.isNotBlank() } ?: return SignInDataDecision.PROCEED
        return if (ownerId == signedInUserId) {
            SignInDataDecision.PROCEED
        } else {
            SignInDataDecision.ASK_BEFORE_REPLACING_OTHER_ACCOUNT_DATA
        }
    }
}

/**
 * Runs the local side of a session end: wipes only when [AccountDataRetentionPolicy] says the end
 * was deliberate, otherwise keeps everything and raises the sign-in-again notice. Returns whether
 * local data was wiped.
 */
internal suspend fun endAccountSession(
    reason: SessionEndReason,
    clearLocalData: suspend () -> Unit,
    onSessionLost: suspend () -> Unit = {},
): Boolean {
    val wipe = AccountDataRetentionPolicy.wipesLocalData(reason)
    if (wipe) clearLocalData() else onSessionLost()
    return wipe
}
