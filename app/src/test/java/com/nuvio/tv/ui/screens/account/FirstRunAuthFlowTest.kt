package com.nuvio.tv.ui.screens.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunAuthFlowTest {

    @Test
    fun `email button on the first-run QR screen opens email sign-in`() {
        assertEquals(
            "B71: first-run 'Sign in with email' must leave the QR screen",
            FirstRunAuthStep.EMAIL,
            FirstRunAuthFlow.onEmailSignInRequested(FirstRunAuthStep.QR, selfHosted = false),
        )
    }

    @Test
    fun `closing email sign-in returns to QR instead of exiting`() {
        assertEquals("back from email goes to QR", FirstRunAuthStep.QR, FirstRunAuthFlow.onEmailSignInClosed())
    }

    @Test
    fun `self-hosted builds stay QR-only`() {
        assertFalse("self-hosted hides the email entry", FirstRunAuthFlow.emailSignInAvailable(selfHosted = true))
        assertEquals(
            "self-hosted request is ignored",
            FirstRunAuthStep.QR,
            FirstRunAuthFlow.onEmailSignInRequested(FirstRunAuthStep.QR, selfHosted = true),
        )
        assertTrue("store builds offer email", FirstRunAuthFlow.emailSignInAvailable(selfHosted = false))
    }
}
