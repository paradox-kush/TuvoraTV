package com.nuvio.tv.ui.screens.account

/** Which sign-in screen the first-run gate (before profiles/home) is showing. */
enum class FirstRunAuthStep { QR, EMAIL }

/**
 * The first-run gate has no NavController, so it switches between the QR and email sign-in
 * screens itself. Pure so it tests without Compose (B71: the gate never wired the email button,
 * leaving "Sign in with email" a no-op on first launch). Self-hosted builds are QR-only, as in
 * NuvioNavHost's AuthSignIn route, so the email entry point is hidden there.
 */
object FirstRunAuthFlow {
    fun emailSignInAvailable(selfHosted: Boolean): Boolean = !selfHosted

    fun onEmailSignInRequested(current: FirstRunAuthStep, selfHosted: Boolean): FirstRunAuthStep =
        if (emailSignInAvailable(selfHosted)) FirstRunAuthStep.EMAIL else current

    /** Back (or "use QR instead") from the email screen returns to QR, never exits the app. */
    fun onEmailSignInClosed(): FirstRunAuthStep = FirstRunAuthStep.QR
}
