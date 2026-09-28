package com.nuvio.tv.ui.screens.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmailTypoSuggestionTest {

    @Test
    fun wrongOrMissingEndingSuggestsProvider() {
        assertEquals("gmail.con", "gmail.com", EmailTypoSuggestion.suggestDomain("gmail.con"))
        assertEquals("gmail.coma", "gmail.com", EmailTypoSuggestion.suggestDomain("gmail.coma"))
        assertEquals("gmail.comh", "gmail.com", EmailTypoSuggestion.suggestDomain("gmail.comh"))
        assertEquals("gmail", "gmail.com", EmailTypoSuggestion.suggestDomain("gmail"))
    }

    @Test
    fun misspellingSuggestsClosestProvider() {
        assertEquals("gmial.com", "gmail.com", EmailTypoSuggestion.suggestDomain("gmial.com"))
        assertEquals("hotmali.com", "hotmail.com", EmailTypoSuggestion.suggestDomain("hotmali.com"))
        assertEquals("yahooo.com", "yahoo.com", EmailTypoSuggestion.suggestDomain("yahooo.com"))
        assertEquals("outlok.com", "outlook.com", EmailTypoSuggestion.suggestDomain("outlok.com"))
    }

    @Test
    fun droppedLeadingLettersSuggestsProvider() {
        assertEquals("ail.com", "gmail.com", EmailTypoSuggestion.suggestDomain("ail.com"))
    }

    @Test
    fun correctOrUnrelatedDomainsHaveNoSuggestion() {
        assertNull("gmail.com", EmailTypoSuggestion.suggestDomain("gmail.com"))
        assertNull("yahoo.co.uk", EmailTypoSuggestion.suggestDomain("yahoo.co.uk"))
        assertNull("tuvora.co", EmailTypoSuggestion.suggestDomain("tuvora.co"))
        assertNull("company.io", EmailTypoSuggestion.suggestDomain("company.io"))
        assertNull("proton.me", EmailTypoSuggestion.suggestDomain("proton.me"))
    }

    @Test
    fun suggestEmailKeepsLocalPartAndFixesDomain() {
        assertEquals("Kush.P@gmail.com", EmailTypoSuggestion.suggestEmail("Kush.P@gmail.con"))
        assertEquals("trimmed", "a@gmail.com", EmailTypoSuggestion.suggestEmail("  a@gmail.con "))
    }

    @Test
    fun suggestEmailNeedsLocalPartAndAt() {
        assertNull("no @", EmailTypoSuggestion.suggestEmail("kush"))
        assertNull("empty local part", EmailTypoSuggestion.suggestEmail("@gmail.con"))
        assertNull("already correct", EmailTypoSuggestion.suggestEmail("kush@gmail.com"))
    }

    @Test
    fun serverRefusalMessageIsExtractedFromRawError() {
        val raw = "Bad Request\nWe can't deliver email to \"gmail.con\". Did you mean gmail.com?\n" +
            "URL: https://example.supabase.co/auth/v1/signup\nHeaders: [x]"
        assertEquals(
            "typo refusal",
            "We can't deliver email to \"gmail.con\". Did you mean gmail.com?",
            signUpRefusalMessage(raw),
        )
        assertEquals(
            "disposable refusal",
            "Please use a permanent email address. Temporary inboxes can't receive account emails.",
            signUpRefusalMessage(
                "x\nPlease use a permanent email address. Temporary inboxes can't receive account emails.\nURL: u",
            ),
        )
        assertNull("unrelated error", signUpRefusalMessage("user_already_exists\nUser already registered"))
    }
}
