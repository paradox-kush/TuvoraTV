package com.nuvio.tv.core.sync

/**
 * A push was attempted with no usable session, and was refused locally instead of being sent as
 * `anon` for the server to reject.
 *
 * Every `sync_push_*` / `sync_delete_*` RPC is `revoke all … from public, anon` + `grant execute …
 * to authenticated`, so a call made without a session doesn't fail as "not signed in" — PostgREST
 * runs it as the `anon` role and Postgres answers
 *
 *     permission denied for function sync_push_watch_progress   (SQLSTATE 42501)
 *
 * Scrobbles fire from playback rather than from a user action, so they kept firing after a session
 * went away — each one a wasted round-trip that logged an error and dropped the write.
 *
 * Returned as a failed [Result] rather than swallowed as a success, because a skipped push must NOT
 * advance `lastSuccessfulPushMs`: that watermark is what tells the merge which local entries have
 * never reached the server and must be protected from a pull that doesn't contain them. Mark a
 * skipped push as succeeded and the next pull treats those entries as remotely deleted.
 *
 * The queued writes go out on the next sign-in, from the existing `preservedLocalItems` re-push in
 * AccountViewModel.pullRemoteData() and from pushLocalDataToRemote(). KMP twin: the mobile/desktop
 * SyncSession.kt.
 */
class SyncNotAuthenticatedException :
    IllegalStateException("Not signed in — sync push skipped and left queued")

/**
 * True when this failure means "the server will refuse every retry too".
 *
 * The startup pull retries a failed cycle three times, which is right for a timeout or a 5xx and
 * pointless for a lapsed session: asking again cannot mint a token, so all three attempts spend
 * their RPCs to collect the same 42501.
 *
 * Deliberately narrow — only the two shapes certain to repeat qualify:
 *
 *  * [SyncNotAuthenticatedException] — this client refused the call before sending it.
 *  * `42501 / permission denied for function …` — PostgREST ran the call as `anon`.
 *
 * A 401 is NOT on the list: supabase-kt refreshes an expired access token underneath us (that is
 * exactly what `withJwtRefreshRetry` exists for), so a lone 401 can genuinely resolve on retry.
 *
 * KMP twin: Throwable.isSyncAuthRefusal() in the mobile/desktop codebase.
 */
fun Throwable.isSyncAuthRefusal(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is SyncNotAuthenticatedException) return true
        val message = current.message?.lowercase()
        if (message != null &&
            ("42501" in message || "permission denied for function" in message)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
