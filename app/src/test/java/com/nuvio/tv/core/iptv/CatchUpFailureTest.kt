package com.nuvio.tv.core.iptv

import androidx.media3.common.PlaybackException
import com.nuvio.tv.core.iptv.CatchUpDialectWalk.FailureKind
import com.nuvio.tv.core.iptv.CatchUpFailure.StalkerLinkError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which playback failures are allowed to advance the dialect ladder, and the Stalker error shapes
 * that deserve their own words.
 *
 * The walk exists because panels disagree about the URL SHAPE. A decode failure means a URL that
 * did reach a stream — trying another shape just replays the same broken recording and burns a
 * connection on a max_connections=1 line. StreamVault walks on network/source/buffer-timeout only;
 * so do we, as a whitelist, so an unclassified error stops rather than churns.
 */
class CatchUpFailureTest {

    @Test
    fun `network and source failures advance the walk`() {
        assertEquals(
            "http status (404 from the wrong path shape)",
            FailureKind.TRANSPORT,
            CatchUpFailure.kindFor(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(
            "connection failed",
            FailureKind.TRANSPORT,
            CatchUpFailure.kindFor(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(
            "connection timed out",
            FailureKind.TRANSPORT,
            CatchUpFailure.kindFor(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
        )
        assertEquals(
            "file not found",
            FailureKind.TRANSPORT,
            CatchUpFailure.kindFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
        assertEquals(
            "buffer ran dry waiting for a source",
            FailureKind.TRANSPORT,
            CatchUpFailure.kindFor(PlaybackException.ERROR_CODE_TIMEOUT),
        )
    }

    /**
     * The panel answered with bytes. A different URL shape reaches the same recording, so the walk
     * stops and the viewer is told the programme is unavailable.
     */
    @Test
    fun `parse and decode failures stop the walk`() {
        assertEquals(
            "malformed container",
            FailureKind.DECODE,
            CatchUpFailure.kindFor(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
        )
        assertEquals(
            "unsupported container",
            FailureKind.DECODE,
            CatchUpFailure.kindFor(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED),
        )
        assertEquals(
            "decoder init failed",
            FailureKind.DECODE,
            CatchUpFailure.kindFor(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
        )
        assertEquals(
            "decoding failed",
            FailureKind.DECODE,
            CatchUpFailure.kindFor(PlaybackException.ERROR_CODE_DECODING_FAILED),
        )
    }

    /** Unknown means unclassified, and an unclassified failure must not churn the ladder. */
    @Test
    fun `an unclassified failure stops the walk`() {
        assertEquals(
            "unspecified",
            FailureKind.DECODE,
            CatchUpFailure.kindFor(PlaybackException.ERROR_CODE_UNSPECIFIED),
        )
        assertEquals("a code we have never seen", FailureKind.DECODE, CatchUpFailure.kindFor(99_999))
    }

    /**
     * Stalker's session cap answers `{"js":{"error":"limit"}}`. "Couldn't open this channel" sends
     * the user to Discord blaming the app; "you are watching on too many devices" sends them to
     * close the other one.
     */
    @Test
    fun `the stalker session cap is its own error`() {
        assertEquals(
            "the documented shape",
            StalkerLinkError.SESSION_LIMIT,
            CatchUpFailure.stalkerLinkErrorOf("""{"js":{"error":"limit"}}"""),
        )
        assertEquals(
            "single quotes, as the middleware source writes it",
            StalkerLinkError.SESSION_LIMIT,
            CatchUpFailure.stalkerLinkErrorOf("""{js:{error:'limit'}}"""),
        )
        assertEquals(
            "bare",
            StalkerLinkError.SESSION_LIMIT,
            CatchUpFailure.stalkerLinkErrorOf("""{"error":"limit"}"""),
        )
    }

    @Test
    fun `link fault is recognised separately`() {
        assertEquals(
            "the portal could not build a link",
            StalkerLinkError.LINK_FAULT,
            CatchUpFailure.stalkerLinkErrorOf("""{"js":{"cmd":"link_fault"}}"""),
        )
    }

    @Test
    fun `an ordinary answer is not an error`() {
        assertEquals(
            "a real link",
            StalkerLinkError.NONE,
            CatchUpFailure.stalkerLinkErrorOf("""{"js":{"cmd":"ffmpeg http://host/stream"}}"""),
        )
        assertEquals("nothing at all", StalkerLinkError.NONE, CatchUpFailure.stalkerLinkErrorOf(null))
        assertEquals("empty", StalkerLinkError.NONE, CatchUpFailure.stalkerLinkErrorOf(""))
    }

    /**
     * "limit" must be the panel's own field, not a substring of a title or a hostname — a channel
     * called "No Limits TV" must not report a session cap.
     */
    @Test
    fun `the word limit elsewhere in the body is not a session cap`() {
        assertEquals(
            "a channel name",
            StalkerLinkError.NONE,
            CatchUpFailure.stalkerLinkErrorOf("""{"js":{"cmd":"ffmpeg http://host/no-limits/stream"}}"""),
        )
    }
}
