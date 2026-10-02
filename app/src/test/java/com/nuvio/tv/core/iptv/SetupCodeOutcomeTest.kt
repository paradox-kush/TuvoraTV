package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Step 2 contract section 3: one pure mapping for preview AND redeem. */
class SetupCodeOutcomeTest {

    private val contacts = ProviderSupport(telegram = "acme_tv")

    @Test
    fun `typed problems map to their sentences`() {
        assertEquals(SetupMessage.EMPTY, SetupCodeOutcome.forProblem(SetupCode.Problem.EMPTY).message)
        assertEquals(SetupMessage.BAD_CHARACTERS, SetupCodeOutcome.forProblem(SetupCode.Problem.BAD_CHARACTERS).message)
        assertEquals(SetupMessage.WRONG_LENGTH, SetupCodeOutcome.forProblem(SetupCode.Problem.WRONG_LENGTH).message)
    }

    @Test
    fun `expired names its cause and carries the contacts when known`() {
        val known = SetupCodeOutcome.forServerCode("expired", support = contacts)
        assertEquals(SetupCodeOutcome.Expired(contacts), known)
        assertEquals(SetupMessage.EXPIRED, known.message)
        assertEquals("no contacts known -> none", SetupCodeOutcome.Expired(null), SetupCodeOutcome.forServerCode("expired"))
        assertEquals("empty contacts -> none", SetupCodeOutcome.Expired(null), SetupCodeOutcome.forServerCode("expired", support = ProviderSupport()))
    }

    @Test
    fun `rate limiting carries retry-after`() {
        assertEquals(SetupCodeOutcome.RateLimited(30), SetupCodeOutcome.forServerCode("rate_limited", retryAfterSec = 30))
        assertEquals(SetupMessage.RATE_LIMITED, SetupCodeOutcome.RateLimited(null).message)
    }

    @Test
    fun `signed-out and anonymous callers are sent to sign in`() {
        assertEquals(SetupCodeOutcome.NeedsSignIn, SetupCodeOutcome.forServerCode("anonymous_not_allowed"))
        assertEquals(SetupCodeOutcome.NeedsSignIn, SetupCodeOutcome.forServerCode("not_authenticated"))
        assertNull(SetupCodeOutcome.NeedsSignIn.message)
    }

    @Test
    fun `every other server code reads as one neutral sentence`() {
        listOf("invalid_code", "not_found", "used", "already_used", "revoked", "suspended", "unavailable", "boom", "", null)
            .forEach { assertEquals("forServerCode($it)", SetupCodeOutcome.Unusable, SetupCodeOutcome.forServerCode(it)) }
        assertEquals(SetupMessage.UNUSABLE, SetupCodeOutcome.Unusable.message)
    }

    @Test
    fun `a gone profile is its own state`() {
        assertEquals(SetupCodeOutcome.ProfileNotFound, SetupCodeOutcome.forServerCode("profile_not_found"))
        assertEquals(SetupMessage.PROFILE_NOT_FOUND, SetupCodeOutcome.ProfileNotFound.message)
    }

    @Test
    fun `the preview route's HTTP statuses map per the contract`() {
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.forPreviewHttp(400, "invalid_code"))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.forPreviewHttp(404, "not_found"))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.forPreviewHttp(409, "used"))
        assertEquals(SetupCodeOutcome.Expired(null), SetupCodeOutcome.forPreviewHttp(410, "expired"))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.forPreviewHttp(410, "revoked"))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.forPreviewHttp(410, "suspended"))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.forPreviewHttp(410, "unavailable"))
        assertEquals(SetupCodeOutcome.RateLimited(12), SetupCodeOutcome.forPreviewHttp(429, "rate_limited", 12))
        assertEquals("a 429 is rate limiting even without a body code", SetupCodeOutcome.RateLimited(null), SetupCodeOutcome.forPreviewHttp(429, null))
        assertEquals("404 without a code = feature off", SetupCodeOutcome.Unusable, SetupCodeOutcome.forPreviewHttp(404, null))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.forPreviewHttp(503, null))
        assertEquals(SetupCodeOutcome.Unusable, SetupCodeOutcome.forPreviewHttp(502, "boom"))
    }

    /** The shipped strings must be the contract's sentences (this pins strings.xml to [SetupMessage]). */
    @Test
    fun `strings xml carries the contract sentences`() {
        val xml = stringsXml()
        val names = mapOf(
            SetupMessage.EMPTY to "setup_code_msg_empty",
            SetupMessage.BAD_CHARACTERS to "setup_code_msg_bad_characters",
            SetupMessage.WRONG_LENGTH to "setup_code_msg_wrong_length",
            SetupMessage.EXPIRED to "setup_code_msg_expired",
            SetupMessage.RATE_LIMITED to "setup_code_msg_rate_limited",
            SetupMessage.NETWORK to "setup_code_msg_network",
            SetupMessage.UNUSABLE to "setup_code_msg_unusable",
            SetupMessage.PROFILE_NOT_FOUND to "setup_code_msg_profile_not_found",
        )
        assertEquals("every message has a resource", SetupMessage.values().toSet(), names.keys)
        names.forEach { (message, name) ->
            val raw = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            assertTrue("$name is missing from strings.xml", raw != null)
            val text = raw!!.replace("\\'", "'")
            assertEquals(name, message.english, text)
        }
    }

    private fun stringsXml(): String {
        val root = generateSequence(File(System.getProperty("user.dir")!!).canonicalFile) { it.parentFile }
            .first { File(it, "app/src/main/res/values/strings.xml").isFile }
        return File(root, "app/src/main/res/values/strings.xml").readText()
    }
}
