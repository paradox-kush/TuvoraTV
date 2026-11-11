package com.nuvio.tv.core.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether any activity of the app is started (visible). Fed from Application's activity callbacks, so a
 * background poll can ask "is anyone looking?" without a Context or a lifecycle owner.
 */
@Singleton
class AppForegroundTracker @Inject constructor() {
    private val foreground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = foreground.asStateFlow()
    private var started = 0

    @Synchronized fun onActivityStarted() { started++; foreground.value = true }
    @Synchronized fun onActivityStopped() { started = (started - 1).coerceAtLeast(0); foreground.value = started > 0 }

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = this@AppForegroundTracker.onActivityStarted()
            override fun onActivityStopped(activity: Activity) = this@AppForegroundTracker.onActivityStopped()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
