package com.nuvio.tv.core.lifecycle

import java.util.concurrent.atomic.AtomicBoolean

/** Process-global bootstrap flag (Invariant S wiring). Android/JVM AtomicBoolean twin. */
object FeatureRegistry {
    private val initialized = AtomicBoolean(false)
    val isInitialized: Boolean get() = initialized.get()
    fun markInitialized() { initialized.set(true) }
    fun initializeForTests() { initialized.set(true) }
}
