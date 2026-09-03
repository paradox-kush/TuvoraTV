package com.nuvio.tv.ui.screens.iptv

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for the field-reported "when I go to IPTV it just closes the app" crash — reproduced on a
 * Google TV Onn and on a fresh Android-TV emulator against the released v1.6.0 APK, whose FATAL was:
 *
 *   java.lang.NullPointerException: Attempt to invoke interface method
 *   'boolean java.util.Collection.isEmpty()' on a null object reference
 *       at …StateFlowImpl.collect / ReadonlyStateFlow.collect        (overlayRepository.uiState.collect)
 *       at …<ViewModel>.<init>                                        (viewModelScope.launch in init)
 *       Suppressed: … Dispatchers.Main.immediate
 *
 * Mechanism (init order): [XtreamLiveGuideViewModel]'s `init` launches
 * `overlayRepository.uiState.collect { if (lastRawChannels.isNotEmpty()) … }`. `viewModelScope`
 * dispatches on `Dispatchers.Main.immediate`, and a `StateFlow` delivers its current value to a new
 * collector INLINE on subscribe — so during construction the collector runs synchronously, before
 * property initializers that appear AFTER the init block have executed. While `lastRawChannels` was
 * declared after `init` it was still the JVM default (null) at that instant, so `.isNotEmpty()`
 * (compiled to `!isEmpty()`) NPE'd and the uncaught exception killed the app the moment IPTV opened.
 * It is data-independent (the overlay StateFlow's seed value is an empty snapshot), which is why every
 * user hit it immediately and why the v1.6.1 `launchSafely` guard — which only wraps the repository's
 * IO scope — did not fix it: this throw is in the guide's own `viewModelScope` collector, on the
 * `lastRawChannels` read that sits OUTSIDE the `withOverlay` try/catch.
 *
 * The two stand-ins below reproduce the exact init-order shape with `Dispatchers.Unconfined`, which —
 * like `Main.immediate` — runs the StateFlow's initial emission inline on subscribe, and route the
 * launch's uncaught exception to a [CoroutineExceptionHandler] exactly as the process's default handler
 * did on device. [FieldAfterInit] is the pre-fix ordering and MUST surface the NPE; [FieldBeforeInit]
 * mirrors the shipped fix (fields declared before `init`) and must construct cleanly. If the overlay
 * fields are ever moved back below the init block, [FieldBeforeInit]-style construction starts throwing.
 */
class LiveGuideOverlayInitOrderTest {

    /** Pre-fix ordering: the init-launched inline collector reads a field initialized AFTER `init`. */
    private class FieldAfterInit(overlay: StateFlow<Int>, scope: CoroutineScope) {
        val observed = mutableListOf<Boolean>()
        init {
            scope.launch {
                overlay.collect { observed.add(lastRawChannels.isNotEmpty()) }
            }
        }
        // Declared AFTER init on purpose: still JVM-null when the inline initial emission is delivered.
        private var lastRawChannels: List<String> = emptyList()
    }

    /** Shipped fix: the same field, declared BEFORE `init`, is already initialized for the inline emit. */
    private class FieldBeforeInit(overlay: StateFlow<Int>, scope: CoroutineScope) {
        private var lastRawChannels: List<String> = emptyList()
        val observed = mutableListOf<Boolean>()
        init {
            scope.launch {
                overlay.collect { observed.add(lastRawChannels.isNotEmpty()) }
            }
        }
    }

    private fun captureUncaught(): Pair<CoroutineScope, AtomicReference<Throwable?>> {
        val caught = AtomicReference<Throwable?>()
        val handler = CoroutineExceptionHandler { _, t -> caught.set(t) }
        return CoroutineScope(Dispatchers.Unconfined + handler) to caught
    }

    @Test
    fun `pre-fix field-after-init ordering NPEs on the inline initial emission`() {
        val (scope, caught) = captureUncaught()
        FieldAfterInit(MutableStateFlow(0), scope)
        val t = caught.get()
        assertTrue("construction should surface the exact on-device crash, was: $t", t is NullPointerException)
    }

    @Test
    fun `shipped fix field-before-init ordering survives construction`() {
        val (scope, caught) = captureUncaught()
        val guide = FieldBeforeInit(MutableStateFlow(0), scope)
        assertNull("no uncaught exception when the field precedes init", caught.get())
        // The inline initial emission was observed as an empty (non-null) list — no crash, nothing to re-apply.
        assertEquals("initial emission observed exactly once", 1, guide.observed.size)
        assertEquals("empty overlay means the re-apply branch is skipped", false, guide.observed.first())
    }
}
