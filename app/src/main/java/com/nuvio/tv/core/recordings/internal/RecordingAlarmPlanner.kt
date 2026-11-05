package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.ArmKind

/**
 * Which AlarmManager call an alarm uses. Pure so the exact/inexact decision tests without a device.
 * Exact alarms are a user permission from Android 12 (API 31); below that every app has them.
 */
internal object RecordingAlarmPlanner {
    enum class Method { SET_EXACT_AND_ALLOW_WHILE_IDLE, SET_AND_ALLOW_WHILE_IDLE }

    data class Plan(val method: Method, val kind: ArmKind)

    fun plan(sdkInt: Int, canScheduleExact: Boolean): Plan =
        if (sdkInt < 31 || canScheduleExact) Plan(Method.SET_EXACT_AND_ALLOW_WHILE_IDLE, ArmKind.EXACT)
        else Plan(Method.SET_AND_ALLOW_WHILE_IDLE, ArmKind.INEXACT)

    /** The permission was withdrawn between the check and the call: the alarm is inexact after all. */
    val FALLBACK = Plan(Method.SET_AND_ALLOW_WHILE_IDLE, ArmKind.INEXACT)
}
