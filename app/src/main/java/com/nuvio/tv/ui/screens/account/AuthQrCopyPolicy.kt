package com.nuvio.tv.ui.screens.account

/**
 * Copy decisions for the TV QR sign-in screen.
 *
 * UX81: the side hint said "TV stays QR-only" right next to a "Sign in with email" button.
 * UX86: the instruction said "enter the short code" while no code was shown. The TV's
 * `start_tv_login_session` returns a QR link and a long session code that cannot be typed anywhere;
 * only a session that carries a short `user_code` and an address to type it at can offer it.
 */
internal object AuthQrCopyPolicy {
    enum class PhoneHint { SCAN_OR_EMAIL, SCAN_ONLY }
    enum class Instruction { SCAN_OR_ENTER_CODE, SCAN_ONLY }
    data class ManualCode(val verificationUri: String, val code: String)

    fun phoneHint(emailSignInAvailable: Boolean): PhoneHint =
        if (emailSignInAvailable) PhoneHint.SCAN_OR_EMAIL else PhoneHint.SCAN_ONLY

    /** The typeable code and where to type it, or null when the session has no short user code. */
    fun manualCode(verificationUri: String?, userCode: String?): ManualCode? {
        val uri = verificationUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val code = userCode?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ManualCode(uri, code)
    }

    fun instruction(manualCode: ManualCode?): Instruction =
        if (manualCode != null) Instruction.SCAN_OR_ENTER_CODE else Instruction.SCAN_ONLY
}
