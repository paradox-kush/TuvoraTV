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
