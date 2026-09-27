package com.nuvio.tv.core.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1: on the emulator a missing stored session ("No entry with the key sb-<ref>-session") made
 * refreshCurrentSessionSerialized return INVALID_SESSION, handleUnexpectedSignedOut saw the
 * "had authenticated" notice mark and ran AccountLocalDataResetService.clearAfterSignOut() — every
 * profile's layout and Continue Watching, including 4 device-only items, erased for good. Only a
 * deliberate end may wipe.
 */
class AccountDataRetentionPolicyTest {

    private val owner = LocalDataOwner(userId = "60dd7cfc-b68e-478d-b794-80fcf57a2530", email = "viewer@example.com")

    @Test
    fun `a lost session never wipes local data`() {
        assertFalse(
            "an unexpected session loss must keep every profile's data",
            AccountDataRetentionPolicy.wipesLocalData(SessionEndReason.SESSION_LOST)
        )
    }

    @Test
    fun `every deliberate end still wipes local data`() {
        listOf(
            SessionEndReason.USER_SIGN_OUT,
            SessionEndReason.ACCOUNT_DELETED,
            SessionEndReason.SYNC_BACKEND_SWITCH,
            SessionEndReason.SWITCHED_ACCOUNT,
        ).forEach { reason ->
            assertTrue("$reason is deliberate and must wipe", AccountDataRetentionPolicy.wipesLocalData(reason))
        }
    }

    @Test
    fun `a missing session with the authenticated notice mark does not clear profile stores`() = runBlocking {
        // The exact emulator shape: the notice store had "had authenticated" set, so the unexpected
        // sign-out was marked (markUnexpectedNuvioLogoutIfNeeded() == true) and the reset ran.
        val noticeMarked = true
        val profileStores = mutableMapOf(
            1 to mutableListOf("tt0111161", "tt0068646", "tt0468569", "tt0071562"),
            2 to mutableListOf("tt0110912"),
        )
        var clearAfterSignOutCalls = 0
        var noticeShown = false

        val wiped = endAccountSession(
            reason = SessionEndReason.SESSION_LOST,
            clearLocalData = {
                clearAfterSignOutCalls++
                profileStores.clear()
            },
            onSessionLost = { noticeShown = noticeMarked },
        )

        assertFalse("a lost session must report that nothing was wiped", wiped)
        assertEquals("clearAfterSignOut must not run on a lost session", 0, clearAfterSignOutCalls)
        assertEquals("every profile's store must survive", 2, profileStores.size)
        assertEquals("the 4 unsynced items must survive", 4, profileStores.getValue(1).size)
        assertTrue("the viewer must be told to sign in again", noticeShown)
    }

    @Test
    fun `a deliberate sign-out still clears profile stores`() = runBlocking {
        val profileStores = mutableMapOf(1 to mutableListOf("tt0111161"))
        var noticeShown = false

        val wiped = endAccountSession(
            reason = SessionEndReason.USER_SIGN_OUT,
            clearLocalData = { profileStores.clear() },
            onSessionLost = { noticeShown = true },
        )

        assertTrue("a deliberate sign-out wipes", wiped)
        assertTrue("a deliberate sign-out clears the profile stores", profileStores.isEmpty())
        assertFalse("a deliberate sign-out shows no lost-session notice", noticeShown)
    }

    @Test
    fun `signing in with no recorded owner proceeds`() {
        assertEquals(
            "a fresh device or one wiped by a deliberate sign-out has no other account's data",
            SignInDataDecision.PROCEED,
            AccountDataRetentionPolicy.decideOnSignIn(owner = null, signedInUserId = owner.userId)
        )
    }

    @Test
    fun `signing back in to the same account proceeds so pending changes sync`() {
        assertEquals(
            "the same account must pick its kept data back up",
            SignInDataDecision.PROCEED,
            AccountDataRetentionPolicy.decideOnSignIn(owner = owner, signedInUserId = owner.userId)
        )
    }

    @Test
    fun `signing in to a different account asks before touching the kept data`() {
        assertEquals(
            "one person's kept data must never be merged into another person's account",
            SignInDataDecision.ASK_BEFORE_REPLACING_OTHER_ACCOUNT_DATA,
            AccountDataRetentionPolicy.decideOnSignIn(owner = owner, signedInUserId = "b0b0b0b0-0000-4000-8000-000000000000")
        )
    }

    @Test
    fun `a blank recorded owner is treated as no owner`() {
        assertEquals(
            "a corrupt owner record must not block sign-in",
            SignInDataDecision.PROCEED,
            AccountDataRetentionPolicy.decideOnSignIn(owner = LocalDataOwner(userId = " ", email = null), signedInUserId = owner.userId)
        )
    }
}
