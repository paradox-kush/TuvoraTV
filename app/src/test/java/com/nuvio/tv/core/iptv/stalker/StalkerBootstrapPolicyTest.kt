package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.stalker.StalkerBootstrapPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- status=1 refusals (get_profile decode: 0 = OK, 1 = refused, 2 = wants do_auth) ---------

    /** A bare `{"status": 1}` with no message is a refusal, never a success. */
    @Test
    fun `status one is a refusal even without a message`() {
        val refusal = StalkerBootstrapPolicy.refusalAfterProfile(status = 1, msg = null, blockMsg = null)
        assertNotNull("status=1 must classify as a refusal", refusal)
        assertFalse("no message means no device-conflict evidence", refusal!!.deviceConflict)
        assertNull(refusal.portalText)
    }

    @Test
    fun `status zero or absent is not a refusal`() {
        assertNull(StalkerBootstrapPolicy.refusalAfterProfile(status = 0, msg = null, blockMsg = null))
        assertNull(StalkerBootstrapPolicy.refusalAfterProfile(status = null, msg = null, blockMsg = null))
    }

    /** status=2 is the login-required path (do_auth) — a different branch from a refusal. */
    @Test
    fun `status two is not a refusal`() {
        assertNull(StalkerBootstrapPolicy.refusalAfterProfile(status = 2, msg = null, blockMsg = null))
    }

    /**
     * The narrow device-conflict set: only phrasings that name the DEVICE BINDING itself. The one
     * refusal with a user remedy (stop the other device / ask the provider to reset the MAC).
     */
    @Test
    fun `device conflict phrases are classified`() {
        for (msg in listOf(
            "Device conflict detected",
            "device_id mismatch",
            "Device ID does not match",
            "device id conflict for this account",
        )) {
            val refusal = StalkerBootstrapPolicy.refusalAfterProfile(status = 1, msg = msg, blockMsg = null)
            assertTrue("expected device-conflict for: $msg", refusal != null && refusal.deviceConflict)
        }
    }

    /** "device" alone appears in unrelated refusals — those must stay generic. */
    @Test
    fun `unrelated refusals stay generic`() {
        for (msg in listOf(
            "Your STB is damaged. Call the provider.",
            "device limit reached",
            "no device selected",
            "Account expired",
        )) {
            val refusal = StalkerBootstrapPolicy.refusalAfterProfile(status = 1, msg = msg, blockMsg = null)
            assertTrue("expected GENERIC refusal for: $msg", refusal != null && !refusal.deviceConflict)
        }
    }

    /** `block_msg` routinely carries markup — strip it before it reaches an error surface. */
    @Test
    fun `block message markup is stripped`() {
        val refusal = StalkerBootstrapPolicy.refusalAfterProfile(
            status = 1, msg = null, blockMsg = "Your STB is <br/>blocked."
        )
        assertEquals("Your STB is blocked.", refusal?.portalText)
    }

    @Test
    fun `msg and block message combine without duplication`() {
        assertEquals(
            "Blocked — Call support",
            StalkerBootstrapPolicy.refusalAfterProfile(status = 1, msg = "Blocked", blockMsg = "Call support")?.portalText
        )
        assertEquals(
            "Blocked",
            StalkerBootstrapPolicy.refusalAfterProfile(status = 1, msg = "Blocked", blockMsg = "Blocked")?.portalText
        )
    }

    /** The conflict may arrive in block_msg rather than msg. */
    @Test
    fun `device conflict is detected in block_msg too`() {
        val refusal = StalkerBootstrapPolicy.refusalAfterProfile(
            status = 1, msg = null, blockMsg = "STB blocked: device id does not match"
        )
        assertTrue(refusal != null && refusal.deviceConflict)
    }
}
