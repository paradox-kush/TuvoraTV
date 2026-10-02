package com.nuvio.tv.core.iptv

/**
 * What the "Enter setup code" screen enables and focuses after the server has answered a code. A verdict on
 * the code (unusable, expired, rate limited, malformed) rejects it: Continue stays off until the code is
 * edited, and focus moves to Delete, so a stray OK can never resubmit the same code (each failed lookup
 * costs a rate-limit strike). A network failure says nothing about the code, so a retry stays possible.
 */
object SetupEntryPolicy {
    enum class FocusTarget { CONTINUE, DELETE }

    fun continueEnabled(complete: Boolean, checking: Boolean, rejected: Boolean): Boolean =
        complete && !checking && !rejected

    fun isRejection(outcome: SetupCodeOutcome): Boolean = when (outcome) {
        is SetupCodeOutcome.Ready, SetupCodeOutcome.NeedsSignIn, SetupCodeOutcome.Network -> false
        else -> true
    }

    /** Where focus goes after [outcome]; null = leave it alone (a preview opens, or sign-in is asked). */
    fun focusAfter(outcome: SetupCodeOutcome): FocusTarget? = when {
        isRejection(outcome) -> FocusTarget.DELETE
        outcome == SetupCodeOutcome.Network -> FocusTarget.CONTINUE
        else -> null
    }
}
