package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.ui.screens.account.AuthQrCopyPolicy.Instruction
import com.nuvio.tv.ui.screens.account.AuthQrCopyPolicy.PhoneHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UX81: the first-run sign-in screen said "TV stays QR-only" next to a "Sign in with email" button.
 * UX86: the QR screen said "enter the short code" while showing no code — the TV's
 * start_tv_login_session returns no typeable user code, so the phrase must only appear with one.
 */
class AuthQrCopyPolicyTest {

    @Test
    fun `hint mentions email when the email button is on screen`() {
        assertEquals("email offered", PhoneHint.SCAN_OR_EMAIL, AuthQrCopyPolicy.phoneHint(emailSignInAvailable = true))
    }

    @Test
    fun `hint never claims the TV is QR only`() {
        assertEquals("no email button", PhoneHint.SCAN_ONLY, AuthQrCopyPolicy.phoneHint(emailSignInAvailable = false))
    }

    @Test
    fun `no user code means the instruction drops the short code phrase`() {
        assertEquals("no code", Instruction.SCAN_ONLY, AuthQrCopyPolicy.instruction(AuthQrCopyPolicy.manualCode(null, null)))
        assertEquals(
            "code but nowhere to type it",
            Instruction.SCAN_ONLY,
            AuthQrCopyPolicy.instruction(AuthQrCopyPolicy.manualCode(null, "ABC234"))
        )
        assertEquals(
            "address but no code",
            Instruction.SCAN_ONLY,
            AuthQrCopyPolicy.instruction(AuthQrCopyPolicy.manualCode("https://tuvora.co/link", " "))
        )
    }

    @Test
    fun `a session with a user code shows it and keeps the phrase`() {
        val manual = AuthQrCopyPolicy.manualCode("https://tuvora.co/link", "ABC234")
        assertEquals("code shown", AuthQrCopyPolicy.ManualCode("https://tuvora.co/link", "ABC234"), manual)
        assertEquals("phrase kept", Instruction.SCAN_OR_ENTER_CODE, AuthQrCopyPolicy.instruction(manual))
    }

    @Test
    fun `blank inputs give no manual code`() {
        assertNull("blank uri", AuthQrCopyPolicy.manualCode("", "ABC234"))
    }
}
