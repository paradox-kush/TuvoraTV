package com.nuvio.tv.ui.screens.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSignUpEligibilityTest {
    @Test
    fun accountCreationRequiresExplicitEligibilityConfirmation() {
        assertFalse(
            canCreateAccount(
                email = "adult@example.com",
                password = "secret1",
                isLoading = false,
                eligibilityConfirmed = false,
            ),
        )
        assertTrue(
            canCreateAccount(
                email = "adult@example.com",
                password = "secret1",
                isLoading = false,
                eligibilityConfirmed = true,
            ),
        )
    }

    @Test
    fun accountCreationRemainsBlockedWhileLoadingOrCredentialsAreMissing() {
        assertFalse(canCreateAccount("", "secret1", false, true))
        assertFalse(canCreateAccount("adult@example.com", "", false, true))
        assertFalse(canCreateAccount("adult@example.com", "secret1", true, true))
    }

    @Test
    fun termsUrlUsesCurrentTuvoraDomain() {
        assertEquals("https://tuvora.co/terms", TUVORA_TERMS_URL)
    }

    @Test
    fun privacyUrlUsesCurrentTuvoraDomain() {
        assertEquals("https://tuvora.co/privacy", TUVORA_PRIVACY_URL)
    }

    @Test
    fun termsVersionPinnedToCurrentPublishedTerms() {
        assertEquals("2026-09-27", TUVORA_TERMS_VERSION)
    }
}
