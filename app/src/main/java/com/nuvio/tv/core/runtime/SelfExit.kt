package com.nuvio.tv.core.runtime

import android.content.Context
import android.os.Process
import com.nuvio.tv.core.analytics.AppExitReporter

/**
 * The one place the app kills its own process on purpose. It records why first (durably), so the next launch reports
 * a tagged `self_exit` instead of a fatal-looking `signaled` foreground exit.
 */
object SelfExit {
    fun kill(context: Context?, tag: String) {
        AppExitReporter.markSelfExit(context, tag)
        Process.killProcess(Process.myPid())
    }
}
