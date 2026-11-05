package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.CatchUpFailure.StalkerLinkError

/**
 * What a Stalker archive replay can end as before or just after its link is opened, and the words
 * the viewer gets for each. Pure, so the mapping tests without a portal or a player.
 *
 * Every outcome carries Retry, and Retry always means a FRESH `create_link`: the link a replay was
 * opened with is single-use, so the spent URL is never re-opened, probed or walked to another shape.
 * Four of them are the portal's own answers or silence (session cap, no recording, link fault, a
 * link that never starts); the other two are the failures that have no portal answer to quote.
 */
object StalkerReplayOutcome {

    enum class Kind {
        /** The account is already streaming elsewhere (`limit`). */
        SESSION_LIMIT,

        /** The portal has no recording of this programme (`nothing_to_play`). */
        NOTHING_TO_PLAY,

        /** The portal could not build a link at all (`link_fault`). */
        LINK_FAULT,

        /** The link opened but no frame arrived within the deadline. */
        NO_ANSWER,

        /** The link played or started and then the engine failed. */
        PLAYBACK_FAILED,

        /** `create_link` itself failed with no portal answer (network, handshake, bad reply). */
        OPEN_FAILED,
    }

    /** A terminal outcome. [retry] is always true: there is no outcome the viewer cannot retry. */
    data class Failure(val kind: Kind, val message: String) {
        val retry: Boolean get() = true
    }

    /**
     * A `create_link` that did not yield a URL. [error] is the portal's refusal when it gave one;
     * [timedOut] is the open's own deadline (the portal never answered).
     */
    fun forOpen(error: StalkerLinkError, timedOut: Boolean = false): Failure = when (error) {
        StalkerLinkError.SESSION_LIMIT -> Failure(Kind.SESSION_LIMIT, StalkerArchiveLink.SESSION_LIMIT_MESSAGE)
        StalkerLinkError.NOTHING_TO_PLAY -> Failure(Kind.NOTHING_TO_PLAY, StalkerArchiveLink.NOTHING_TO_PLAY_MESSAGE)
        StalkerLinkError.LINK_FAULT -> Failure(Kind.LINK_FAULT, StalkerArchiveLink.ARCHIVE_LINK_FAULT_MESSAGE)
        StalkerLinkError.NONE ->
            if (timedOut) Failure(Kind.NO_ANSWER, NO_ANSWER_MESSAGE) else Failure(Kind.OPEN_FAILED, OPEN_FAILED_MESSAGE)
    }

    /** No frame within the startup deadline of an opened link. */
    fun forStartupHang(): Failure = Failure(Kind.NO_ANSWER, NO_ANSWER_MESSAGE)

    /** The engine failed on an opened link. Only a fresh link can recover it. */
    fun forPlaybackError(): Failure = Failure(Kind.PLAYBACK_FAILED, PLAYBACK_FAILED_MESSAGE)

    const val NO_ANSWER_MESSAGE = "The portal didn't start this programme in time - press Retry for a fresh link"
    const val PLAYBACK_FAILED_MESSAGE = "The portal's playback link stopped working - press Retry for a fresh link"
    const val OPEN_FAILED_MESSAGE = "Couldn't reach the portal for this programme - press Retry"
}
