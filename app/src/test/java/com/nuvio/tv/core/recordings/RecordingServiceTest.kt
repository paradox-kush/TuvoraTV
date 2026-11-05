package com.nuvio.tv.core.recordings

import android.app.Application
import android.content.Intent
import android.os.Looper
import com.nuvio.tv.core.recordings.api.FireResult
import com.nuvio.tv.core.recordings.api.ListingInfo
import com.nuvio.tv.core.recordings.api.Recording
import com.nuvio.tv.core.recordings.api.RecordingCapabilities
import com.nuvio.tv.core.recordings.api.RecordingChannel
import com.nuvio.tv.core.recordings.api.RecordingSchedule
import com.nuvio.tv.core.recordings.api.RecordingsAccess
import com.nuvio.tv.core.recordings.api.RecordingsPort
import com.nuvio.tv.core.recordings.api.RecordingsState
import com.nuvio.tv.core.recordings.api.ScheduleResult
import com.nuvio.tv.core.recordings.internal.AndroidRecordingAlarms
import com.nuvio.tv.core.recordings.internal.RecordingService
import com.nuvio.tv.core.recordings.internal.RecordingSystemReceiver
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

private class FakePort(
    val onFire: suspend (String) -> FireResult = { FireResult.Saved },
    val onRearm: suspend () -> Unit = {},
) : RecordingsPort {
    val cancelled = ArrayList<String>()
    override val state: StateFlow<RecordingsState> = MutableStateFlow(RecordingsState())
    override val capabilities = RecordingCapabilities.ANDROID
    override fun playableUri(recording: Recording): String? = null
    override val progress: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    override suspend fun start(nowMs: Long) {}
    override suspend fun scheduleProgramme(channel: RecordingChannel, title: String, episode: String?, description: String?, startMs: Long, endMs: Long, nowMs: Long): ScheduleResult = ScheduleResult.CannotRecord
    override suspend fun addSeriesRule(channel: RecordingChannel, title: String, keepLast: Int, listings: List<ListingInfo>, nowMs: Long): List<RecordingSchedule> = emptyList()
    override suspend fun expandSeries(ruleId: String, listings: List<ListingInfo>, nowMs: Long): List<RecordingSchedule> = emptyList()
    override suspend fun cancel(scheduleId: String) { cancelled += scheduleId }
    override suspend fun removeSeriesRule(ruleId: String, cancelUpcoming: Boolean) {}
    override suspend fun deleteRecording(id: String) {}
    override suspend fun retry(recordingId: String, nowMs: Long) {}
    override suspend fun rearmAll(nowMs: Long) = onRearm()
    override suspend fun overdueIds(nowMs: Long): List<String> = emptyList()
    override suspend fun sweepOverdue(nowMs: Long) {}
    override suspend fun fire(scheduleId: String, nowMs: Long): FireResult = onFire(scheduleId)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecordingServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun intent(action: String, id: String) =
        Intent(app, RecordingService::class.java).setAction(action).putExtra(AndroidRecordingAlarms.EXTRA_SCHEDULE_ID, id)

    private fun awaitStopped(shadow: org.robolectric.shadows.ShadowService): Boolean {
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (shadow.isStoppedBySelf) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `a stale notification Cancel for nothing running stops the service`() {
        val port = FakePort().also { RecordingsAccess.register(it) }
        val ctl = Robolectric.buildService(RecordingService::class.java, intent(RecordingService.ACTION_CANCEL, "gone")).create()
        ctl.startCommand(0, 7)
        val shadow = shadowOf(ctl.get())
        assertTrue("the service must not stay started with nothing to do", awaitStopped(shadow))
        assertEquals(listOf("gone"), port.cancelled)
    }

    @Test
    fun `the service stops itself with the latest start id not blindly`() {
        RecordingsAccess.register(FakePort())
        val ctl = Robolectric.buildService(RecordingService::class.java, intent(RecordingService.ACTION_FIRE, "s1")).create()
        ctl.startCommand(0, 3)
        val shadow = shadowOf(ctl.get())
        assertTrue(awaitStopped(shadow))
        assertEquals("stopSelf(startId): a newer start must not be killed by an older finish", 3, shadow.stopSelfId)
    }

    @Test
    fun `an exception in rearmAll is contained and the broadcast is always finished`() = runBlocking {
        var finished = 0
        RecordingSystemReceiver.rearmAndFinish(FakePort(onRearm = { throw IOException("disk") }), 1L) { finished++ }
        assertEquals(1, finished)
    }

    @Test
    fun `cancellation in rearmAll is rethrown and the broadcast still finished`() = runBlocking {
        var finished = 0
        var thrown: Throwable? = null
        try {
            RecordingSystemReceiver.rearmAndFinish(FakePort(onRearm = { throw CancellationException("x") }), 1L) { finished++ }
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue("cancellation must propagate", thrown is CancellationException)
        assertEquals(1, finished)
    }
}
