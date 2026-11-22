package com.nuvio.tv.core.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The keep-alive service is always started with startForegroundService(). Stopping it before
 * onStartCommand has called startForeground() crashes the app with
 * ForegroundServiceDidNotStartInTimeException (AOSP ActiveServices.bringDownServiceLocked),
 * so a stop() that arrives while the start is pending must wait for the promotion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], application = Application::class)
class ExternalPlaybackKeepAliveServiceTest {
    private lateinit var context: RecordingContext

    @Before
    fun prepare() {
        assumeTrue(com.nuvio.tv.BuildConfig.FEATURE_EXTERNAL_PLAYBACK_KEEP_ALIVE_ENABLED)
        context = RecordingContext(RuntimeEnvironment.getApplication())
        ExternalPlaybackKeepAliveService.stop(context)
        context.drain()
    }

    @Test
    fun stopWhileStartPendingWaitsForPromotionThenStops() {
        ExternalPlaybackKeepAliveService.start(context)
        val request = context.takeCall("foreground-start")
        ExternalPlaybackKeepAliveService.stop(context)
        context.assertNoCall()

        val controller = Robolectric.buildService(ExternalPlaybackKeepAliveService::class.java).create()
        try {
            controller.get().onStartCommand(request, 0, 1)
            assertNotNull(
                "startForeground must be called before the service stops",
                shadowOf(controller.get()).lastForegroundNotification,
            )
            assertTrue("deferred stop must run once promoted", shadowOf(controller.get()).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun stopAfterPromotionStopsImmediately() {
        ExternalPlaybackKeepAliveService.start(context)
        val request = context.takeCall("foreground-start")
        val controller = Robolectric.buildService(ExternalPlaybackKeepAliveService::class.java).create()
        try {
            controller.get().onStartCommand(request, 0, 1)
            assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
            ExternalPlaybackKeepAliveService.stop(context)
            context.takeCall("stop")
        } finally {
            controller.destroy()
        }
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        private val calls = LinkedBlockingQueue<Pair<String, Intent>>()

        override fun getApplicationContext(): Context = this

        override fun startService(service: Intent): ComponentName? {
            calls.put("start" to service)
            return service.component
        }

        override fun startForegroundService(service: Intent): ComponentName? {
            calls.put("foreground-start" to service)
            return service.component
        }

        override fun stopService(service: Intent): Boolean {
            calls.put("stop" to service)
            return true
        }

        fun drain() {
            while (calls.poll() != null) Unit
        }

        fun assertNoCall() {
            assertNull("unexpected service call", calls.poll(300, TimeUnit.MILLISECONDS)?.first)
        }

        fun takeCall(expected: String): Intent {
            val call = calls.poll(2, TimeUnit.SECONDS)
            assertNotNull("expected a $expected call", call)
            assertEquals("service call", expected, call!!.first)
            return call.second
        }
    }
}
