package com.nuvio.tv.ui.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Walk a Compose [Context] back to the hosting Activity.
 *
 * Needed by the handful of settings that can only take effect on a fresh Activity — a theme change,
 * or promoting a profile (which renames the per-profile DataStore files that references across the
 * graph are still holding).
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
