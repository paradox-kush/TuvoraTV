package com.nuvio.tv.core.iptv

/** What a playlist edit does after its provider check: whether it is saved, and what to tell the user. */
internal data class PlaylistEditOutcome(val save: Boolean, val warning: String?)

/**
 * B60 — decides what an edited playlist's live provider check may do to the save (twin of Mobile's).
 *
 * Decision 2026-09-27: **save anyway and warn**. A playlist is user-authored configuration (a lazy
 * write in Android's offline-first terms), and the edit that most often fails a check from the
 * current network is the one the user most needs to keep: a provider moving domains (DNS not yet
 * propagated, a WAF, a geo-block). So a failed check never discards what was typed; the reason is
 * shown on the playlist's row instead. Only an edit that changes how the provider is reached is checked
 * ([needsVerify], see [XtreamAccount.sameConnectionAs]); adding a NEW playlist still requires a pass.
 */
internal object PlaylistEditVerifyPolicy {
    fun needsVerify(old: XtreamAccount, edited: XtreamAccount): Boolean = !edited.sameConnectionAs(old)

    fun outcome(verifyResult: Result<Unit>): PlaylistEditOutcome {
        val failure = verifyResult.exceptionOrNull() ?: return PlaylistEditOutcome(save = true, warning = null)
        val reason = failure.message?.trim()?.takeIf { it.isNotEmpty() } ?: "no response"
        return PlaylistEditOutcome(save = true, warning = "Saved, but the provider couldn't be checked: $reason")
    }
}
