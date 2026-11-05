package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.internal.ExactAlarmSettingsLaunch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back from "Alarms & reminders" landed on the launcher: NEW_TASK from a non-task context. From an Activity there is none. */
class ExactAlarmSettingsLaunchTest {
    @Test
    fun activityContextJoinsTheAppTask() {
        assertFalse("an Activity must not force a new task (Back would leave Tuvora)", ExactAlarmSettingsLaunch.needsNewTask(fromActivity = true))
    }

    @Test
    fun nonActivityContextNeedsNewTask() {
        assertTrue("a non-Activity context cannot start an activity without NEW_TASK", ExactAlarmSettingsLaunch.needsNewTask(fromActivity = false))
    }
}
