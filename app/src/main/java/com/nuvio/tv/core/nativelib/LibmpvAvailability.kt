package com.nuvio.tv.core.nativelib

import android.util.Log

/**
 * B287 (twin of NuvioMobile `LibmpvAvailability.kt`): libmpv is used only where its native libraries load.
 * A device whose bundled libmpv fails to load (seen on a 32-bit Android 8.1 tablet on mobile) threw
 * UnsatisfiedLinkError from mpv's constructor and crashed the app; such a device plays on ExoPlayer instead.
 */
internal object LibmpvAvailabilityPolicy {
    /** [wanted]: the chosen engine is libmpv. [loadable] is asked only then (it loads native code). */
    fun usable(wanted: Boolean, loadable: () -> Boolean): Boolean = wanted && loadable()

    /** The engine to run: libmpv only where it loads, otherwise ExoPlayer. */
    fun engineFor(engine: com.nuvio.tv.data.local.InternalPlayerEngine): com.nuvio.tv.data.local.InternalPlayerEngine =
        if (engine == com.nuvio.tv.data.local.InternalPlayerEngine.MVP_PLAYER && !usable(wanted = true) { LibmpvNativeProbe.loadable }) {
            com.nuvio.tv.data.local.InternalPlayerEngine.EXOPLAYER
        } else {
            engine
        }
}

/** Loads the same libraries, in the same order, as `is.xyz.mpv.MPV`'s constructor; once per process. */
internal object LibmpvNativeProbe {
    val loadable: Boolean by lazy {
        try {
            System.loadLibrary("mpv")
            System.loadLibrary("player")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("LibmpvNativeProbe", "libmpv native libraries failed to load; using ExoPlayer")
            false
        }
    }
}
