package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 contract section 1: the golden vectors, shared verbatim with every platform and nuvio-web. */
class SetupCodeTest {

    private fun valid(input: String) = (SetupCode.normalize(input) as? SetupCode.Normalized.Valid)?.value
    private fun problem(input: String) = (SetupCode.normalize(input) as? SetupCode.Normalized.Invalid)?.problem

    @Test
    fun `every spelling of a code normalizes to the same 12 characters`() {
        listOf(
            "TUV-ABCD-EFGH-JKMN", "tuv-abcd-efgh-jkmn", "  TUV ABCD EFGH JKMN ", "ABCD-EFGH-JKMN",
            "abcdefghjkmn", "TUVABCDEFGHJKMN",
        ).forEach { assertEquals("normalize(\"$it\")", "ABCDEFGHJKMN", valid(it)) }
    }

    @Test
    fun `a bare code that itself starts with TUV survives the prefix rule`() {
        assertEquals("TUVWXYZ23456", valid("TUVWXYZ23456"))
        assertEquals("TUVWXYZ23456", valid("TUV-TUVW-XYZ2-3456"))
    }

    @Test
    fun `characters outside the alphabet are refused before any request`() {
        listOf("TUV-ABCD-EFGH-JKM0", "TUV-ABCD-EFGH-JKMI", "ABCD'; drop table--", "ABCDEFGHJKM%")
            .forEach { assertEquals("normalize(\"$it\")", SetupCode.Problem.BAD_CHARACTERS, problem(it)) }
    }

    @Test
    fun `empty and blank input is Empty`() {
        assertEquals(SetupCode.Problem.EMPTY, problem(""))
        assertEquals(SetupCode.Problem.EMPTY, problem(" - "))
        assertEquals("null is Empty", SetupCode.Problem.EMPTY, (SetupCode.normalize(null) as SetupCode.Normalized.Invalid).problem)
    }

    @Test
    fun `wrong length is WrongLength`() {
        assertEquals(SetupCode.Problem.WRONG_LENGTH, problem("ABCD-EFGH"))
        assertEquals(SetupCode.Problem.WRONG_LENGTH, problem("ABCDEFGHJKMNP"))
    }

    @Test
    fun `format groups a valid code and leaves anything else alone`() {
        assertEquals("TUV-ABCD-EFGH-JKMN", SetupCode.format("abcdefghjkmn"))
        assertEquals("nonsense!", SetupCode.format("nonsense!"))
    }

    @Test
    fun `the alphabet is the 31 unambiguous characters`() {
        assertEquals(31, SetupCode.ALPHABET.length)
        assertEquals("no 0 O 1 I L", false, SetupCode.ALPHABET.any { it in "01OIL" })
    }

    @Test
    fun `live format groups what has been typed so far`() {
        assertEquals("", SetupCode.liveFormat(""))
        assertEquals("", SetupCode.liveFormat("  - "))
        assertEquals("TUV-A", SetupCode.liveFormat("a"))
        assertEquals("TUV-ABCD", SetupCode.liveFormat("abcd"))
        assertEquals("TUV-ABCD-E", SetupCode.liveFormat("abcde"))
        assertEquals("TUV-ABCD-EFGH-JKMN", SetupCode.liveFormat("abcdefghjkmn"))
    }

    @Test
    fun `live format never doubles a typed or pasted prefix`() {
        assertEquals("TUV-ABCD-EFGH-JKMN", SetupCode.liveFormat("TUV-ABCD-EFGH-JKMN"))
        assertEquals("TUV-AB", SetupCode.liveFormat("tuv-ab"))
        assertEquals("TUV-AB", SetupCode.liveFormat("TUV ab"))
        assertEquals("TUV-ABCD-EFGH-JKMN", SetupCode.liveFormat("TUVABCDEFGHJKMN"))
    }

    @Test
    fun `live format keeps a bare TUV start as code characters and caps at twelve`() {
        assertEquals("TUV-TUVW", SetupCode.liveFormat("TUVW"))
        assertEquals("TUV-TUVW-XYZ2-3456", SetupCode.liveFormat("TUVWXYZ23456"))
        assertEquals("TUV-ABCD-EFGH-JKMN", SetupCode.liveFormat("abcdefghjkmnpqrst"))
    }

    @Test
    fun `live format keeps a bad character so the person sees what they typed`() {
        assertEquals("TUV-AB0D", SetupCode.liveFormat("ab0d"))
        assertFalse(SetupCode.isComplete("ab0d"))
    }

    @Test
    fun `isComplete is true only for a full valid code`() {
        assertFalse(SetupCode.isComplete("ABCDEFGHJKM"))
        assertTrue(SetupCode.isComplete("ABCDEFGHJKMN"))
        assertFalse("a bad character is not complete", SetupCode.isComplete("ABCDEFGHJKM0"))
    }

    @Test
    fun `parse is null for an invalid code`() {
        assertNull(SetupCode.parse("nope"))
        assertEquals("ABCDEFGHJKMN", SetupCode.parse("TUV-ABCD-EFGH-JKMN"))
    }
}
