package com.nuvio.tv.data.local

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B24: the persisted playlist blob must classify into distinct states so the sync push never wipes
 * the server from an absent/reset store or an unreadable blob. Only a clean present array (Valid,
 * including an explicit `[]` from a deliberate delete-all) may full-replace.
 */
class XtreamAccountLoadOutcomeTest {

    private val gson = Gson()
    private fun row(id: String) =
        """{"id":"$id","name":"P","baseUrl":"http://h:8080","username":"u","password":"p"}"""

    @Test
    fun `a clean present array is Valid and may full-replace`() {
        val o = decodeXtreamAccountsOutcome(gson, "[${row("a")},${row("b")}]")
        assertTrue("clean array is Valid", o is XtreamAccountLoadOutcome.Valid)
        assertEquals(2, o.accounts.size)
        assertTrue("a clean decode may push", o.canFullReplace)
    }

    @Test
    fun `an explicit empty array is Valid and pushable — a deliberate delete-all`() {
        val o = decodeXtreamAccountsOutcome(gson, "[]")
        assertTrue("[] is Valid", o is XtreamAccountLoadOutcome.Valid)
        assertTrue("[] is empty", o.accounts.isEmpty())
        assertTrue("deleting the last playlist still pushes", o.canFullReplace)
    }

    @Test
    fun `a null or blank blob is Absent and must NOT push — fresh install or corruption-reset`() {
        for (blank in listOf(null, "", "   ")) {
            val o = decodeXtreamAccountsOutcome(gson, blank)
            assertTrue("blank/null is Absent", o is XtreamAccountLoadOutcome.Absent)
            assertFalse("an absent store must not wipe the server", o.canFullReplace)
        }
    }

    @Test
    fun `a non-array blob is Corrupt and must NOT push`() {
        assertTrue(decodeXtreamAccountsOutcome(gson, "{\"x\":1}") is XtreamAccountLoadOutcome.Corrupt)
        assertTrue(decodeXtreamAccountsOutcome(gson, "not json at all") is XtreamAccountLoadOutcome.Corrupt)
        assertFalse("corrupt must not push", decodeXtreamAccountsOutcome(gson, "garbage").canFullReplace)
    }

    @Test
    fun `a non-object element among valid rows is dropped as Recovered and withheld`() {
        val o = decodeXtreamAccountsOutcome(gson, "[${row("a")},42]")
        assertTrue("partial decode is Recovered", o is XtreamAccountLoadOutcome.Recovered)
        assertEquals("the valid row is recovered", 1, o.accounts.size)
        assertFalse("a recovered subset must not full-replace", o.canFullReplace)
    }

    @Test
    fun `decodeXtreamAccountsJson still yields the account list for the read flow`() {
        assertEquals(1, decodeXtreamAccountsJson(gson, "[${row("a")}]").size)
        assertTrue("null reads empty", decodeXtreamAccountsJson(gson, null).isEmpty())
        assertTrue("garbage reads empty (read flow tolerates)", decodeXtreamAccountsJson(gson, "garbage").isEmpty())
    }
}
