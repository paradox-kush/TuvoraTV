package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.CatchUpFailure.StalkerLinkError
import com.nuvio.tv.core.iptv.XtreamAccount

/**
 * Mints the single-use playback link of one archived Stalker programme (`type=tv_archive`
 * `create_link`, from the portal's own EPG programme id). Every call is a FRESH mint: the link a
 * replay opened with is spent by playing, and a retry must never reuse it. An injected seam so the
 * coordinator's routing and refusal handling test without a portal.
 */
fun interface StalkerArchiveSource {
    suspend fun mint(account: XtreamAccount, programmeId: String): Minted

    sealed interface Minted {
        data class Link(val url: String) : Minted {
            override fun toString(): String = "Minted.Link(hasUrl=true)"
        }

        /** No URL: [error] is the portal's refusal when it gave one, else [StalkerLinkError.NONE]. */
        data class Refused(val error: StalkerLinkError) : Minted
    }

    companion object {
        /** Nothing can be minted: the replay is unavailable. */
        val NONE = StalkerArchiveSource { _, _ -> Minted.Refused(StalkerLinkError.NONE) }
    }
}
