package com.nuvio.tv.playback.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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

/** The one Android focus owner for both Media3 and libmpv. */
class AndroidAudioFocusPort(context: Context) : AudioFocusPort {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
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
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).run {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        setOnAudioFocusChangeListener(listener)
        setWillPauseWhenDucked(false)
        build()
    }

    override fun events(): Flow<AudioFocusEvent> = mutableEvents.asSharedFlow()

    override suspend fun acquire(): PlaybackResult<Unit> =
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            PlaybackResult.Success(Unit)
        } else {
            failure(FailurePhase.ENGINE_START)
        }

    override suspend fun abandon(): PlaybackResult<Unit> =
        if (audioManager.abandonAudioFocusRequest(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            PlaybackResult.Success(Unit)
        } else {
            failure(FailurePhase.RELEASE)
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
