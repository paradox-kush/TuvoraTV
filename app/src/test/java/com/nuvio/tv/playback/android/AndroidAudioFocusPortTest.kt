package com.nuvio.tv.playback.android

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.nuvio.tv.playback.core.PlaybackResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioFocusRequest(.Builder) is API 26. minSdk is 24 and Fire OS 6 (the Fire TV Stick 4K) is API 25,
 * so on API < 26 the port must use the legacy stream-focus API and must NEVER build the O+ request
 * (referencing it on-device there is a NoClassDefFoundError). The on-device verification failure is
 * inherently device-only and cannot be reproduced off a device; what these plain-JVM tests pin is the
 * routing the SDK guard performs and that the O+ request factory is untouched below API 26.
 */
class AndroidAudioFocusPortTest {
    @Test
    fun `API 25 (Fire OS 6) uses the legacy stream focus API and never builds the O+ request`() = runBlocking {
        val am = mockk<AudioManager>()
        every { am.requestAudioFocus(any<AudioManager.OnAudioFocusChangeListener>(), any(), any()) } returns
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        every { am.abandonAudioFocus(any<AudioManager.OnAudioFocusChangeListener>()) } returns
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        var factoryCalls = 0
        val port = AndroidAudioFocusPort(am, sdkInt = 25, focusRequestFactory = { factoryCalls++; mockk() })

        assertTrue(port.acquire() is PlaybackResult.Success)
        assertTrue(port.abandon() is PlaybackResult.Success)
        assertEquals("the O+ AudioFocusRequest must not be built on API 25", 0, factoryCalls)
        verify(exactly = 1) {
            am.requestAudioFocus(any<AudioManager.OnAudioFocusChangeListener>(), AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        verify(exactly = 1) { am.abandonAudioFocus(any<AudioManager.OnAudioFocusChangeListener>()) }
    }

    @Test
    fun `API 26+ uses the modern AudioFocusRequest`() = runBlocking {
        val am = mockk<AudioManager>()
        val request = mockk<AudioFocusRequest>()
        every { am.requestAudioFocus(request) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        every { am.abandonAudioFocusRequest(request) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        val port = AndroidAudioFocusPort(am, sdkInt = Build.VERSION_CODES.O, focusRequestFactory = { request })

        assertTrue(port.acquire() is PlaybackResult.Success)
        assertTrue(port.abandon() is PlaybackResult.Success)
        verify(exactly = 1) { am.requestAudioFocus(request) }
        verify(exactly = 1) { am.abandonAudioFocusRequest(request) }
    }

    @Test
    fun `a denied focus request is a failure`() = runBlocking {
        val am = mockk<AudioManager>()
        every { am.requestAudioFocus(any<AudioManager.OnAudioFocusChangeListener>(), any(), any()) } returns
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        val port = AndroidAudioFocusPort(am, sdkInt = 25, focusRequestFactory = { mockk() })
        assertTrue(port.acquire() is PlaybackResult.Failure)
    }
}
