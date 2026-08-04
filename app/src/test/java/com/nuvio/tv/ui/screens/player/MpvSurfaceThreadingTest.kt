package com.nuvio.tv.ui.screens.player

import android.view.SurfaceHolder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the "no mpv call ever runs on Main" rule at the one place it is easiest to lose:
 * the SurfaceView callbacks, which UIKit-style run synchronously inside `View.layout`.
 *
 * `BaseMPVView.surfaceChanged` writes `android-surface-size` with a plain
 * `mpv_set_property`. That takes the mpv core lock, which a live demuxer holds for seconds,
 * so inheriting it stalls the main thread every time the surface resizes:
 *
 *   main  pthread_cond_wait <- mpv_set_property <- MPV.setPropertyString
 *         <- SurfaceView.updateSurface <- SurfaceView.setFrame <- View.layout
 *
 * That was a reproducible ANR on the phone's docked <-> fullscreen toggle. Deleting our
 * override silently reintroduces it — the app still compiles and still plays — so assert
 * the override exists rather than trusting review to catch it.
 */
class MpvSurfaceThreadingTest {

    @Test
    fun `surfaceChanged is overridden so the resize write never runs on the main thread`() {
        // getDeclaredMethod only finds methods declared on this class, so this throws
        // NoSuchMethodException the moment the override is removed and BaseMPVView's
        // blocking version is inherited again.
        val declaringClass = NuvioMpvSurfaceView::class.java.getDeclaredMethod(
            "surfaceChanged",
            SurfaceHolder::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).declaringClass

        assertEquals(
            "surfaceChanged must stay overridden on NuvioMpvSurfaceView: inheriting " +
                "BaseMPVView's version puts a blocking mpv_set_property on the main thread, " +
                "which ANRs whenever the surface resizes.",
            NuvioMpvSurfaceView::class.java,
            declaringClass,
        )
    }
}
