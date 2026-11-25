package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.internal.RecordingServiceLaunch
import com.nuvio.tv.core.recordings.internal.RecordingStartOrigin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B286 (twin of NuvioMobile): app start handed an overdue recording to the service with startForegroundService and a
 * slow cold start missed its deadline (ForegroundServiceDidNotStartInTimeException), crash-looping every launch.
 */
class RecordingServiceLaunchTest {

    @Test
    fun appStartWithAnActivityVisibleUsesAPlainStart() {
        assertFalse("no foreground deadline from the visible app", RecordingServiceLaunch.needsForegroundStart(RecordingStartOrigin.VISIBLE_APP))
    }

    @Test
    fun anAlarmFromTheBackgroundStillNeedsTheForegroundStart() {
        assertTrue("alarm keeps startForegroundService", RecordingServiceLaunch.needsForegroundStart(RecordingStartOrigin.ALARM))
    }
}
