package com.nuvio.tv.core.recordings.internal

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.nuvio.tv.core.recordings.api.ArmKind
import com.nuvio.tv.core.recordings.api.RecordingAlarms

/**
 * AlarmManager behind the [RecordingAlarms] port. Exact (setExactAndAllowWhileIdle) when the user
 * allowed exact alarms, else setAndAllowWhileIdle and INEXACT, so the UI can say "may start late".
 * Each alarm is a distinct PendingIntent (data URI carries the schedule id), so arming again
 * replaces it and disarming cancels exactly one.
 */
internal class AndroidRecordingAlarms(private val context: Context) : RecordingAlarms {
    private val manager: AlarmManager? get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    override fun exactAllowed(): Boolean = canScheduleExact()

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < 31 || (manager?.canScheduleExactAlarms() == true)

    override fun arm(scheduleId: String, atMs: Long): ArmKind {
        val am = manager ?: return ArmKind.NONE
        val pending = pendingIntent(scheduleId, create = true) ?: return ArmKind.NONE
        var plan = RecordingAlarmPlanner.plan(Build.VERSION.SDK_INT, canScheduleExact())
        try {
            when (plan.method) {
                RecordingAlarmPlanner.Method.SET_EXACT_AND_ALLOW_WHILE_IDLE ->
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
                RecordingAlarmPlanner.Method.SET_AND_ALLOW_WHILE_IDLE ->
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            }
        } catch (_: SecurityException) {
            plan = RecordingAlarmPlanner.FALLBACK
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        }
        return plan.kind
    }

    override fun disarm(scheduleId: String) {
        val pending = pendingIntent(scheduleId, create = false) ?: return
        manager?.cancel(pending)
        pending.cancel()
    }

    private fun pendingIntent(scheduleId: String, create: Boolean): PendingIntent? {
        val intent = fireIntent(context, scheduleId)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, scheduleId.hashCode(), intent, flags)
    }

    companion object {
        const val ACTION_FIRE = "com.nuvio.tv.recordings.action.FIRE"
        const val EXTRA_SCHEDULE_ID = "schedule_id"

        fun fireIntent(context: Context, scheduleId: String): Intent =
            Intent(context, RecordingAlarmReceiver::class.java)
                .setAction(ACTION_FIRE)
                .setData(Uri.Builder().scheme("tuvora-rec").authority("fire").appendPath(scheduleId).build())
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId)

        /**
         * Where the user can allow exact alarms (Android 12+), for the UI to open. Null where no permission is
         * needed (below API 31) OR where this device has no such settings screen: an Android TV / Fire TV
         * build may not ship "Alarms & reminders" (unverified), so the UI must treat null as "stay inexact".
         */
        fun exactAlarmSettingsIntent(context: Context): Intent? {
            if (Build.VERSION.SDK_INT < 31) return null
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return intent.takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }
}
