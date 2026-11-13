package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream

/**
 * A matched source can be LISTED with a deferred (not yet minted) play url - a Stalker edition:
 * listing stays free, the link is minted only for the edition the viewer picks (a line is commonly sold
 * with max_connections=1, so eager mints starve the real playback). Every pick site must mint first;
 * this is the one decision, kept pure so it tests without the player (Wave 3 / P0 - the in-player source
 * and episode switch were the sites that handed the engine the placeholder).
 */
object DeferredStreamPolicy {

    sealed interface Outcome {
        /** Not deferred: play [stream] as it is. */
        data object Plain : Outcome

        /** Deferred and minted: play this copy, whose url is the real link. */
        data class Minted(val stream: Stream) : Outcome

        /** Deferred but the source would not issue a link: keep what is playing, say so. */
        data object Unavailable : Outcome
    }

    suspend fun resolve(
        stream: Stream,
        isDeferred: (String?) -> Boolean,
        mint: suspend (String) -> String?,
    ): Outcome {
        val url = stream.url
        if (!isDeferred(url)) return Outcome.Plain
        val minted = mint(url.orEmpty())
        return if (minted.isNullOrBlank() || isDeferred(minted)) Outcome.Unavailable else Outcome.Minted(stream.copy(url = minted))
    }
}
