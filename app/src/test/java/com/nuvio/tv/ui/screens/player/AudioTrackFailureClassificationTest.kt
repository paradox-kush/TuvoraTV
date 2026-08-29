package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for [isAudioTrackFailure].
 *
 * A fatal AudioTrack *write* failure (`ERROR_CODE_AUDIO_TRACK_WRITE_FAILED` / 5002, e.g.
 * `AudioTrack.ERROR_DEAD_OBJECT` (-6) on an E-AC-3 passthrough track) must be classified the
 * same as an *init* failure (5001) so it routes into the safe-audio → audio-disabled recovery
 * ladder instead of landing on the fatal "Playback Error" screen.
 */
class AudioTrackFailureClassificationTest {

    @Test
    fun `init failure code is an audio-track failure`() {
        assertTrue(isAudioTrackFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, ""))
    }

    @Test
    fun `write failure code is an audio-track failure`() {
        assertTrue(isAudioTrackFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, ""))
    }

    @Test
    fun `on-screen 5002 code is an audio-track failure`() {
        // Mirrors "AudioTrack write failed: -6 [5002]" reported on Android TV (Hisense 65U6G).
        assertTrue(isAudioTrackFailure(5002, ""))
    }

    @Test
    fun `write failure message is matched under a generic error code`() {
        assertTrue(
            isAudioTrackFailure(
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                "MediaCodecAudioRenderer error ... AudioTrack write failed: -6"
            )
        )
    }

    @Test
    fun `init failure message is matched under a generic error code`() {
        assertTrue(
            isAudioTrackFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, "AudioTrack init failed")
        )
    }

    @Test
    fun `message matching is case-insensitive`() {
        assertTrue(
            isAudioTrackFailure(PlaybackException.ERROR_CODE_UNSPECIFIED, "AUDIOTRACK WRITE FAILED")
        )
    }

    @Test
    fun `unrelated decoder error is not an audio-track failure`() {
        assertFalse(
            isAudioTrackFailure(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                "MediaCodecVideoRenderer error ... decoder failed"
            )
        )
    }

    @Test
    fun `unrelated io error with empty message is not an audio-track failure`() {
        assertFalse(
            isAudioTrackFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, "")
        )
    }

    @Test
    fun `video format exceeding decoder capabilities is deterministic and not retryable`() {
        val error = ExoPlaybackException.createForRenderer(
            IllegalStateException("decoder rejected profile and level"),
            "MediaCodecVideoRenderer",
            0,
            Format.Builder().setSampleMimeType("video/hevc").build(),
            C.FORMAT_EXCEEDS_CAPABILITIES,
            false,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        assertTrue(error.isDeterministicVideoCapabilityFailure())
        assertFalse(isRetryablePlaybackError(error))
    }

    // Regression: the legacy generic decoding-failure ladder was deleted with the clean-pipeline
    // hardening, which left a deterministic AUDIO-codec decode failure (e.g. DTS/E-AC-3 on a box
    // without the codec — shape 4003 on the audio renderer, NOT AudioTrack 5001/5002) retrying
    // the identical config until the budget was spent and then showing the fatal error screen.
    // isAudioDecoderFailure routes it back into the safe-audio → PCM → audio-disabled ladder.
    @Test
    fun `audio renderer decode failure is an audio decoder failure`() {
        val error = ExoPlaybackException.createForRenderer(
            IllegalStateException("Decoder failed: c2.android.dts.decoder"),
            "MediaCodecAudioRenderer",
            0,
            Format.Builder().setSampleMimeType("audio/vnd.dts").build(),
            C.FORMAT_HANDLED,
            false,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        assertTrue(error.isAudioDecoderFailure())
    }

    @Test
    fun `audio renderer runtime check failure is an audio decoder failure`() {
        val error = ExoPlaybackException.createForRenderer(
            IllegalStateException("runtime check failed"),
            "MediaCodecAudioRenderer",
            0,
            Format.Builder().setSampleMimeType("audio/eac3").build(),
            C.FORMAT_HANDLED,
            false,
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
        )

        assertTrue(error.isAudioDecoderFailure())
    }

    // The cross-domain routing bug the old generic branch had: a VIDEO decode failure must never
    // enter the audio recovery ladder.
    @Test
    fun `video renderer decode failure is not an audio decoder failure`() {
        val error = ExoPlaybackException.createForRenderer(
            IllegalStateException("decoder rejected profile and level"),
            "MediaCodecVideoRenderer",
            0,
            Format.Builder().setSampleMimeType("video/hevc").build(),
            C.FORMAT_EXCEEDS_CAPABILITIES,
            false,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        assertFalse(error.isAudioDecoderFailure())
    }

    @Test
    fun `non-renderer source error is not an audio decoder failure`() {
        val error = ExoPlaybackException.createForSource(
            java.io.IOException("read failed"),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )

        assertFalse(error.isAudioDecoderFailure())
    }
}
