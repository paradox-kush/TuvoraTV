package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.stalker.StalkerBootstrapPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class StalkerBootstrapPolicyTest {

    /** The common case: portal says nothing special, so handshake + profile was the whole bootstrap. */
    @Test
    fun `a plain portal needs nothing more`() {
        assertEquals(
            emptyList<Step>(),
            StalkerBootstrapPolicy.stepsAfterProfile(authAccess = null, status = null, hasCredentials = false)
        )
        assertEquals(
            emptyList<Step>(),
            StalkerBootstrapPolicy.stepsAfterProfile(authAccess = true, status = 0, hasCredentials = true)
        )
    }

    /** Ministra-style gating: the portal has not decided what this box may see until modules load. */
    @Test
    fun `auth_access false means fetch the modules`() {
        assertEquals(
            listOf(Step.GET_MODULES),
            StalkerBootstrapPolicy.stepsAfterProfile(authAccess = false, status = 0, hasCredentials = false)
        )
    }

    @Test
    fun `a non-zero status with credentials means authorize explicitly`() {
        assertEquals(
            listOf(Step.DO_AUTH),
            StalkerBootstrapPolicy.stepsAfterProfile(authAccess = true, status = 2, hasCredentials = true)
        )
    }

    /**
     * Without a login there is nothing to send. Letting the portal's own refusal stand tells the
     * user more than firing a do_auth we know is incomplete.
     */
    @Test
    fun `a non-zero status without credentials asks for nothing`() {
        assertEquals(
            emptyList<Step>(),
            StalkerBootstrapPolicy.stepsAfterProfile(authAccess = true, status = 2, hasCredentials = false)
        )
    }

    /** A portal wanting both wants to know who this is before saying what they may watch. */
    @Test
    fun `authorization comes before modules`() {
        assertEquals(
            listOf(Step.DO_AUTH, Step.GET_MODULES),
            StalkerBootstrapPolicy.stepsAfterProfile(authAccess = false, status = 2, hasCredentials = true)
        )
    }

    /**
     * Most portals omit these fields entirely. Absence must mean "nothing further needed" — an
     * unwanted do_auth is a wasted call on a healthy portal and a rejection on a strict one.
     */
    @Test
    fun `absent fields are not treated as failures`() {
        assertEquals(
            emptyList<Step>(),
            StalkerBootstrapPolicy.stepsAfterProfile(authAccess = null, status = null, hasCredentials = true)
        )
    }
}
