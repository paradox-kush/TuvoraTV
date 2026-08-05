package com.nuvio.tv.core.rec

import android.app.Application
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecEventSettingsPrivacyTest {
    @Test
    fun optOutPurgesPendingEventsBeforeIdentityRotation() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_rec_events", 0).edit().clear().commit()
        val queueFile = File(context.filesDir, "rec-events-queue.jsonl")
        queueFile.writeText("sensitive pending event")

        val identity = RecEventIdentity(context)
        val before = identity.deviceId()
        val settings = RecEventSettings(context, identity)
        var purged = false
        settings.registerQueuePurger { purged = true }

        settings.setEnabled(false)
        assertFalse(settings.enabled.value)
        assertTrue(purged)
        assertFalse(queueFile.exists())

        purged = false
        settings.setEnabled(true)
        assertTrue("queue purge must run before re-enable", purged)
        assertTrue(settings.enabled.value)
        assertNotEquals(before, identity.deviceId())
    }
}
