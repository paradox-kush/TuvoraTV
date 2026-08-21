package com.nuvio.tv.ui.screens.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for "switch playlist, the old live TV keeps playing" (root-caused 2026-08-20).
 *
 * The guide's minimized preview is one shared player across account switches. The auto-resume that
 * runs on a switch used to skip whenever ANY preview channel was set — so a stale preview left over
 * from the previous account blocked re-tuning, and the old stream kept decoding. The corrected
 * decision skips only when a preview for the NEW account is already up; [GuidePreviewOwnership] pins
 * that "does this preview belong to this account" question.
 *
 * NOTE: JUnit argument order is assertEquals(message, expected, actual) — opposite of kotlin.test.
 */
class GuidePreviewOwnershipTest {

    // Real account ids are "baseUrl|username", so they carry ':' (URL scheme/port) and '|'.
    private val accA = "http://prov-a.tv:8080|alice"
    private val accB = "http://prov-b.tv:8080|bob"

    @Test
    fun `a preview of the account belongs to it`() {
        assertTrue(GuidePreviewOwnership.belongsTo("xtream:$accA:live:42", accA))
    }

    @Test
    fun `a preview from a different account does not belong - so a switch re-tunes`() {
        // The bug: the old-account preview was treated as "present" and blocked the resume.
        assertFalse(GuidePreviewOwnership.belongsTo("xtream:$accA:live:42", accB))
    }

    @Test
    fun `no preview belongs to any account`() {
        assertFalse(GuidePreviewOwnership.belongsTo(null, accA))
    }

    @Test
    fun `ownership does not bleed across accounts sharing a username prefix`() {
        val acc1 = "http://prov.tv:8080|user1"
        val acc12 = "http://prov.tv:8080|user12"
        // user12's channel must NOT read as owned by user1 — the ':' terminating the prefix guards it.
        assertFalse(GuidePreviewOwnership.belongsTo("xtream:$acc12:live:7", acc1))
        assertTrue(GuidePreviewOwnership.belongsTo("xtream:$acc1:live:7", acc1))
    }
}
