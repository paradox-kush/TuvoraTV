package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.ArmKind
import com.nuvio.tv.core.recordings.internal.RecordingAlarmPlanner
import com.nuvio.tv.core.recordings.internal.RecordingAlarmPlanner.Method
import com.nuvio.tv.core.recordings.internal.RecordingSaveClassifier
import com.nuvio.tv.core.recordings.api.RecordingProblem
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Test
import org.junit.Assert.assertEquals

class RecordingAlarmPlannerTest {
    @Test
    fun `before Android 12 every app has exact alarms`() {
        val p = RecordingAlarmPlanner.plan(sdkInt = 30, canScheduleExact = false)
        assertEquals(Method.SET_EXACT_AND_ALLOW_WHILE_IDLE, p.method)
        assertEquals(ArmKind.EXACT, p.kind)
    }

    @Test
    fun `with the exact alarm permission the alarm is exact`() {
        val p = RecordingAlarmPlanner.plan(sdkInt = 34, canScheduleExact = true)
        assertEquals(Method.SET_EXACT_AND_ALLOW_WHILE_IDLE, p.method)
        assertEquals(ArmKind.EXACT, p.kind)
    }

    @Test
    fun `without the permission on Android 12 plus the alarm is inexact and says so`() {
        val p = RecordingAlarmPlanner.plan(sdkInt = 31, canScheduleExact = false)
        assertEquals(Method.SET_AND_ALLOW_WHILE_IDLE, p.method)
        assertEquals(ArmKind.INEXACT, p.kind)
        assertEquals(ArmKind.INEXACT, RecordingAlarmPlanner.FALLBACK.kind)
    }

    @Test
    fun `no network is offline whatever the error`() {
        val k = RecordingSaveClassifier.forIo(ConnectException(), stalled = false, online = false)
        assertEquals(RecordingProblem.NETWORK, k.problem)
        assertEquals(true, k.offline)
        assertEquals(true, RecordingSaveClassifier.forHttp(404, online = false).offline)
    }

    @Test
    fun `timeouts and watchdog stalls are STALLED while online`() {
        assertEquals(RecordingProblem.STALLED, RecordingSaveClassifier.forIo(SocketTimeoutException(), false, true).problem)
        assertEquals(RecordingProblem.STALLED, RecordingSaveClassifier.forIo(null, true, true).problem)
        assertEquals(RecordingProblem.NETWORK, RecordingSaveClassifier.forIo(ConnectException(), false, true).problem)
        assertEquals(false, RecordingSaveClassifier.forIo(ConnectException(), false, true).offline)
    }

    @Test
    fun `4xx is the provider refusing and 5xx is a transient network fault`() {
        assertEquals(RecordingProblem.PROVIDER_REFUSED, RecordingSaveClassifier.forHttp(403, true).problem)
        assertEquals(RecordingProblem.PROVIDER_REFUSED, RecordingSaveClassifier.forHttp(456, true).problem)
        assertEquals(RecordingProblem.NETWORK, RecordingSaveClassifier.forHttp(503, true).problem)
    }
}
