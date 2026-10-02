package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the code screen enables and focuses after the server has answered a code. */
class SetupEntryPolicyTest {

    @Test
    fun `continue needs a complete code, no request in flight, and no unchanged rejection`() {
        assertTrue(SetupEntryPolicy.continueEnabled(complete = true, checking = false, rejected = false))
        assertFalse("incomplete", SetupEntryPolicy.continueEnabled(complete = false, checking = false, rejected = false))
        assertFalse("in flight", SetupEntryPolicy.continueEnabled(complete = true, checking = true, rejected = false))
        assertFalse("the same rejected code must not be resubmitted by a stray OK", SetupEntryPolicy.continueEnabled(complete = true, checking = false, rejected = true))
    }

    @Test
    fun `a verdict on the code rejects it and moves focus to Delete`() {
        listOf(
            SetupCodeOutcome.Unusable,
            SetupCodeOutcome.Expired(null),
            SetupCodeOutcome.RateLimited(30),
            SetupCodeOutcome.Problem(SetupCode.Problem.WRONG_LENGTH),
        ).forEach {
            assertTrue("$it rejects", SetupEntryPolicy.isRejection(it))
            assertEquals("$it focus", SetupEntryPolicy.FocusTarget.DELETE, SetupEntryPolicy.focusAfter(it))
        }
    }

    @Test
    fun `a network failure says nothing about the code so retrying it stays possible`() {
        assertFalse(SetupEntryPolicy.isRejection(SetupCodeOutcome.Network))
        assertEquals(SetupEntryPolicy.FocusTarget.CONTINUE, SetupEntryPolicy.focusAfter(SetupCodeOutcome.Network))
    }

    @Test
    fun `a ready preview or a sign-in request is no error and moves no focus`() {
        assertNull(SetupEntryPolicy.focusAfter(SetupCodeOutcome.NeedsSignIn))
        assertFalse(SetupEntryPolicy.isRejection(SetupCodeOutcome.NeedsSignIn))
    }
}
