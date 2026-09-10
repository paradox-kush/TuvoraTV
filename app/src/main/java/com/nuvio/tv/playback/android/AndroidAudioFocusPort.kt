package com.nuvio.tv.playback.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.nuvio.tv.playback.core.AudioFocusEvent
import com.nuvio.tv.playback.core.AudioFocusPort
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.Retryability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The one Android focus owner for both Media3 and libmpv.
 *
 * [AudioFocusRequest] and its Builder are API 26 (Android 8.0 / Oreo). The app's `minSdk` is 24 and
 * Fire OS 6 — the OS on the Fire TV Stick 4K — is API 25, so referencing the request on the
 * construction path crashes those devices with a `NoClassDefFoundError` (ART defers the verification
 * failure to the first use of the O+ instruction). Both the modern request and the legacy
 * stream-based focus API are therefore guarded on [sdkInt], and the O+ request is built lazily via
 * [focusRequestFactory] so its class is only ever loaded on the branch that can run it. Behaviour is
 * otherwise identical: same media attributes, same listener, same duck policy.
 *
 * [sdkInt] and [focusRequestFactory] are injectable so both branches are unit-testable without a
 * device (the on-device NoClassDefFoundError is inherently device-only and cannot be reproduced off
 * one; what the tests pin is that the guard routes API < 26 to the legacy API and never touches the
 * O+ request).
 */
class AndroidAudioFocusPort internal constructor(
    private val audioManager: AudioManager,
    private val sdkInt: Int,
    private val focusRequestFactory: (AudioManager.OnAudioFocusChangeListener) -> AudioFocusRequest,
) : AudioFocusPort {

    constructor(context: Context) : this(
        audioManager = context.applicationContext.getSystemService(AudioManager::class.java),
        sdkInt = Build.VERSION.SDK_INT,
        focusRequestFactory = ::defaultFocusRequest,
    )

    private val mutableEvents = MutableSharedFlow<AudioFocusEvent>(extraBufferCapacity = 8)
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        val event = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> AudioFocusEvent.GAIN
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusEvent.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusEvent.LOSS_DUCK
            AudioManager.AUDIOFOCUS_LOSS -> AudioFocusEvent.LOSS_PERMANENT
            else -> null
        }
        event?.let(mutableEvents::tryEmit)
    }

    // Built only when the O+ branch first runs (never on API 24/25). The factory isolates the O+ class
    // reference from AndroidAudioFocusPort's own verification.
    private val focusRequest: AudioFocusRequest by lazy(LazyThreadSafetyMode.NONE) { focusRequestFactory(listener) }

    override fun events(): Flow<AudioFocusEvent> = mutableEvents.asSharedFlow()

    override suspend fun acquire(): PlaybackResult<Unit> {
        val granted = if (sdkInt >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return if (granted) PlaybackResult.Success(Unit) else failure(FailurePhase.ENGINE_START)
    }

    override suspend fun abandon(): PlaybackResult<Unit> {
        val abandoned = if (sdkInt >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(listener) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return if (abandoned) PlaybackResult.Success(Unit) else failure(FailurePhase.RELEASE)
    }

    private fun failure(phase: FailurePhase) = PlaybackResult.Failure(
        PlaybackFailure(
            code = FailureCode.AUDIO_OUTPUT_FAILED,
            domain = FailureDomain.AUDIO,
            phase = phase,
            retryability = Retryability.FATAL,
        ),
    )
}

/** The production API-26+ focus request. A top-level function so the O+ class it references is only
 *  loaded when the O+ branch actually calls it (never on API 24/25). */
@RequiresApi(Build.VERSION_CODES.O)
private fun defaultFocusRequest(listener: AudioManager.OnAudioFocusChangeListener): AudioFocusRequest =
    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setOnAudioFocusChangeListener(listener)
        .setWillPauseWhenDucked(false)
        .build()
