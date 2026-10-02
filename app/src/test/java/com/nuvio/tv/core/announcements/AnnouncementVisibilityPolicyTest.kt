package com.nuvio.tv.core.announcements

import com.nuvio.tv.domain.model.Announcement
import com.nuvio.tv.domain.model.AuthState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * UX84 (TV twin of Mobile/Desktop AnnouncementVisibilityPolicy): a "we've updated our Privacy Policy"
 * notice must not greet people who never saw the old policy — accounts created under the current
 * terms, and installs first opened after the notice started — while existing users still see it.
 */
class AnnouncementVisibilityPolicyTest {

    // The wire format PostgREST returns for a timestamptz column.
    private val startsAtWire = "2026-09-28T06:00:00.123456+00:00"
    private val startsAtMs = Instant.parse("2026-09-28T06:00:00.123456Z").toEpochMilli()
    private val hour = 60L * 60 * 1000

    private fun item(id: String, kind: String, startsAt: String? = startsAtWire) =
        Announcement(id = id, title = "Title $id", body = "Body $id", kind = kind, startsAt = startsAt)

    private val policy = item("p", "policy")
    private val news = item("n", "update")

    private fun viewer(signIn: AnnouncementSignIn, installFirstSeenAtMs: Long = 0L) =
        AnnouncementViewer(signIn, installFirstSeenAtMs)

    private fun account(createdAtMs: Long?, terms: String? = null) = AnnouncementSignIn.Account(createdAtMs, terms)

    // --- accounts created under the current terms ---

    @Test
    fun `policy notice is hidden from an account created after it started`() {
        assertFalse(AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(createdAtMs = startsAtMs + hour))))
    }

    @Test
    fun `policy notice is hidden from an account created exactly when it started`() {
        assertFalse(AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(createdAtMs = startsAtMs))))
    }

    @Test
    fun `policy notice is hidden when the accepted terms version is from the day it started or later`() {
        assertFalse("same day", AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(null, terms = "2026-09-28"))))
        assertFalse("later", AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(null, terms = "2026-10-15"))))
    }

    // --- existing users keep seeing it ---

    @Test
    fun `policy notice is shown to an account created before it started`() {
        assertTrue(AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(startsAtMs - hour, terms = "2026-08-04"))))
    }

    @Test
    fun `policy notice is shown to an existing account on a brand new install`() {
        // A new device for an existing account: the account decides, not the install.
        val v = viewer(account(createdAtMs = startsAtMs - hour), installFirstSeenAtMs = startsAtMs + hour)
        assertTrue(AnnouncementVisibilityPolicy.isVisible(policy, v))
    }

    @Test
    fun `policy notice is shown when the account creation time is unknown`() {
        assertTrue(AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(createdAtMs = null))))
    }

    @Test
    fun `an unparseable terms version does not hide the notice`() {
        assertTrue("v2", AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(startsAtMs - hour, terms = "v2"))))
        assertTrue("blank", AnnouncementVisibilityPolicy.isVisible(policy, viewer(account(startsAtMs - hour, terms = ""))))
    }

    // --- installs that never ran under the old terms ---

    @Test
    fun `policy notice is hidden on a signed out install first opened after it started`() {
        val v = viewer(AnnouncementSignIn.NoAccount, installFirstSeenAtMs = startsAtMs + hour)
        assertFalse(AnnouncementVisibilityPolicy.isVisible(policy, v))
    }

    @Test
    fun `policy notice is shown on a signed out install that predates it`() {
        val v = viewer(AnnouncementSignIn.NoAccount, installFirstSeenAtMs = startsAtMs - hour)
        assertTrue("older install", AnnouncementVisibilityPolicy.isVisible(policy, v))
        // An unknown install time (0) counts as existing rather than hiding the notice.
        assertTrue("unknown", AnnouncementVisibilityPolicy.isVisible(policy, viewer(AnnouncementSignIn.NoAccount, 0L)))
    }

    @Test
    fun `policy notice waits while sign in is still resolving`() {
        assertFalse(AnnouncementVisibilityPolicy.isVisible(policy, viewer(AnnouncementSignIn.Resolving)))
    }

    @Test
    fun `a policy notice with an unreadable start time is shown rather than guessed away`() {
        assertTrue("blank", AnnouncementVisibilityPolicy.isVisible(item("b", "policy", ""), viewer(account(startsAtMs + hour))))
        assertTrue("null", AnnouncementVisibilityPolicy.isVisible(item("c", "policy", null), viewer(account(startsAtMs + hour))))
    }

    @Test
    fun `a Z suffixed start time parses too`() {
        val z = item("z", "policy", "2026-09-28T06:00:00Z")
        assertFalse(AnnouncementVisibilityPolicy.isVisible(z, viewer(account(createdAtMs = startsAtMs + hour))))
    }

    @Test
    fun `kind matching ignores case`() {
        assertFalse(AnnouncementVisibilityPolicy.isVisible(item("P", "Policy"), viewer(account(startsAtMs + hour))))
    }

    // --- other kinds are untouched ---

    @Test
    fun `non policy announcements ignore account age and install age`() {
        assertTrue("account", AnnouncementVisibilityPolicy.isVisible(news, viewer(account(startsAtMs + hour))))
        assertTrue("install", AnnouncementVisibilityPolicy.isVisible(news, viewer(AnnouncementSignIn.NoAccount, startsAtMs + hour)))
        assertTrue("resolving", AnnouncementVisibilityPolicy.isVisible(news, viewer(AnnouncementSignIn.Resolving)))
    }

    @Test
    fun `pick falls through a hidden policy notice to the next announcement`() {
        val v = viewer(account(createdAtMs = startsAtMs + hour))
        assertEquals("falls through", "n", AnnouncementVisibilityPolicy.pick(listOf(policy, news), emptySet(), v)?.id)
        assertNull("dismissed", AnnouncementVisibilityPolicy.pick(listOf(policy, news), setOf("n"), v))
    }

    // --- mapping the auth state ---

    @Test
    fun `auth state maps to the sign in the policy needs`() {
        val record = AnnouncementAccountRecord(userId = "u1", createdAtMs = 42L, acceptedTermsVersion = "2026-09-27")
        assertEquals("loading", AnnouncementSignIn.Resolving, AnnouncementVisibilityPolicy.signInFrom(AuthState.Loading, record))
        assertEquals("signed out", AnnouncementSignIn.NoAccount, AnnouncementVisibilityPolicy.signInFrom(AuthState.SignedOut, record))
        assertEquals(
            "account",
            AnnouncementSignIn.Account(42L, "2026-09-27"),
            AnnouncementVisibilityPolicy.signInFrom(AuthState.FullAccount("u1", "a@b.c"), record)
        )
    }

    @Test
    fun `a session user that is not the signed in account contributes nothing`() {
        val other = AnnouncementAccountRecord(userId = "someone-else", createdAtMs = 42L, acceptedTermsVersion = "2026-09-27")
        assertEquals(
            "other user",
            AnnouncementSignIn.Account(null, null),
            AnnouncementVisibilityPolicy.signInFrom(AuthState.FullAccount("u1", "a@b.c"), other)
        )
        assertEquals(
            "no record",
            AnnouncementSignIn.Account(null, null),
            AnnouncementVisibilityPolicy.signInFrom(AuthState.FullAccount("u1", "a@b.c"), null)
        )
    }

    @Test
    fun `terms version is read from sign up metadata`() {
        assertEquals("present", "2026-09-27", AnnouncementVisibilityPolicy.termsVersionFrom(buildJsonObject { put("terms_version", "2026-09-27") }))
        assertNull("absent", AnnouncementVisibilityPolicy.termsVersionFrom(buildJsonObject { put("other", "x") }))
        assertNull("not a string", AnnouncementVisibilityPolicy.termsVersionFrom(buildJsonObject { put("terms_version", JsonPrimitive(5)) }))
        assertNull("null", AnnouncementVisibilityPolicy.termsVersionFrom(null))
    }
}
