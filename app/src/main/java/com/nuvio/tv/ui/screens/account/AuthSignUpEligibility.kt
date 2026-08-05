package com.nuvio.tv.ui.screens.account

internal const val TUVORA_TERMS_URL = "https://tuvora.co/terms"

internal fun canCreateAccount(
    email: String,
    password: String,
    isLoading: Boolean,
    eligibilityConfirmed: Boolean,
): Boolean = email.isNotBlank() &&
    password.isNotBlank() &&
    !isLoading &&
    eligibilityConfirmed
