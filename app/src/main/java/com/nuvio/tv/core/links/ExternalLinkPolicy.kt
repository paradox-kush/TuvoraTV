package com.nuvio.tv.core.links

/**
 * Decides what happens when the TV is asked to open a web link.
 *
 * Many Android TV / Fire TV devices ship no browser, so `startActivity(ACTION_VIEW)` throws
 * `ActivityNotFoundException` (a [RuntimeException]); some handlers also refuse with a
 * [SecurityException]. We deliberately *try* rather than pre-check with `resolveActivity`: on
 * API 30+ package-visibility rules make that return null even when a browser exists. Any refusal
 * falls back to the QR hand-off so the viewer can still read the page on their phone.
 */
object ExternalLinkPolicy {
    sealed interface Outcome {
        data object Opened : Outcome
        data class ShowQr(val url: String) : Outcome
    }

    fun open(url: String, launch: (String) -> Unit): Outcome =
        try {
            launch(url)
            Outcome.Opened
        } catch (_: RuntimeException) {
            Outcome.ShowQr(url)
        }
}
