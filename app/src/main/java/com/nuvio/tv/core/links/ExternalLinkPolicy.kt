package com.nuvio.tv.core.links

/**
 * Decides what happens when the TV is asked to open a web link.
 *
 * Many Android TV / Fire TV devices ship no browser, and they fail in two different ways:
 * some throw `ActivityNotFoundException` from `startActivity(ACTION_VIEW)`, others (e.g. the stock
 * Android TV image) swallow the intent and show a system "You don't have an app that can do this"
 * toast — a dead end the app never hears about. So the decision is made BEFORE launching:
 * [canResolve] is whether any activity resolves the VIEW intent (the manifest declares a
 * `<queries>` VIEW/BROWSABLE/https entry so Android 11+ package visibility can't hide browsers).
 * If nothing resolves, show the QR hand-off without launching. If something resolves, launch, and
 * still treat any launch refusal ([RuntimeException]: ActivityNotFound, SecurityException) as QR.
 */
object ExternalLinkPolicy {
    sealed interface Outcome {
        data object Opened : Outcome
        data class ShowQr(val url: String) : Outcome
    }

    fun open(url: String, canResolve: Boolean, launch: (String) -> Unit): Outcome {
        if (!canResolve) return Outcome.ShowQr(url)
        return try {
            launch(url)
            Outcome.Opened
        } catch (_: RuntimeException) {
            Outcome.ShowQr(url)
        }
    }
}
