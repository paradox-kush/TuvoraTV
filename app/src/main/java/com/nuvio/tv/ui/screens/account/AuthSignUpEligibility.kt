package com.nuvio.tv.ui.screens.account

internal const val TUVORA_TERMS_URL = "https://tuvora.co/terms"
internal const val TUVORA_PRIVACY_URL = "https://tuvora.co/privacy"

/** Terms of Use version recorded in sign-up metadata. Bump when the published Terms change. */
internal const val TUVORA_TERMS_VERSION = "2026-09-27"

internal fun canCreateAccount(
    email: String,
    password: String,
    isLoading: Boolean,
    eligibilityConfirmed: Boolean,
): Boolean = email.isNotBlank() &&
    password.isNotBlank() &&
    !isLoading &&
    eligibilityConfirmed
