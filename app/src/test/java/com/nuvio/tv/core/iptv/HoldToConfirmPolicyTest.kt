package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 contract section 7: hold OK 2.0 s to confirm Detach / Remove on TV. */
class HoldToConfirmPolicyTest {
    private val policy = HoldToConfirmPolicy()

    @Test
    fun `golden table`() {
        assertEquals(0f, policy.progress(0), 0f); assertFalse(policy.isConfirmed(0))
        assertEquals(0.5f, policy.progress(1000), 0f); assertFalse(policy.isConfirmed(1000))
        assertEquals(0.9995f, policy.progress(1999), 0.0001f); assertFalse("1999 ms is not enough", policy.isConfirmed(1999))
        assertEquals(1f, policy.progress(2000), 0f); assertTrue(policy.isConfirmed(2000))
        assertEquals(1f, policy.progress(3000), 0f); assertTrue(policy.isConfirmed(3000))
    }

    @Test
    fun `a quick press confirms nothing and a negative hold is zero`() {
        assertFalse(policy.isConfirmed(80))
        assertEquals(0f, policy.progress(-5), 0f)
    }

    @Test
    fun `the default hold is two seconds`() {
        assertEquals(2_000L, HoldToConfirmPolicy.DEFAULT_HOLD_MS)
    }

    @Test
    fun `releasing early explains itself, a fresh or finished hold does not`() {
        assertEquals(HoldToConfirmPolicy.Hint.TOO_SHORT, policy.hintAfterRelease(90))
        assertEquals(HoldToConfirmPolicy.Hint.TOO_SHORT, policy.hintAfterRelease(1999))
        assertEquals(HoldToConfirmPolicy.Hint.HOLD, policy.hintAfterRelease(0))
        assertEquals(HoldToConfirmPolicy.Hint.HOLD, policy.hintAfterRelease(2000))
    }
}
