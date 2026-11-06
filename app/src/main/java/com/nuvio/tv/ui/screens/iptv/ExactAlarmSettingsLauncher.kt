package com.nuvio.tv.ui.screens.iptv

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.nuvio.tv.core.recordings.internal.ExactAlarmSettingsLaunch

/** Opens the "allow exact times" screen in the app's own task when possible, so Back returns to Tuvora. */
internal fun launchExactAlarmSettings(context: Context, intent: Intent) {
    if (ExactAlarmSettingsLaunch.needsNewTask(context is Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
