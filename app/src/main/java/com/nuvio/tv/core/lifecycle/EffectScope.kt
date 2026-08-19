package com.nuvio.tv.core.lifecycle

/** A revert handle. [dispose] runs the inverse at most once. */
fun interface Disposable {
    fun dispose()
}

/**
 * Thread-safe LIFO revert scope (Invariant T — tracked disposal). Hand-port of the NuvioMobile/
 * NuvioDesktop EffectScope to NuvioTV's Android/JVM codebase (per the parity rule: same behaviour,
 * platform-appropriate mechanism — plain `synchronized` here, not the KMP atomicfu lock). Contracts:
 *  1. A throwing revert never aborts the rest — it goes to [onRevertFailure]; teardown continues.
 *  2. Registering on an already-disposed scope runs the revert immediately, returns an inert handle.
 *  3. Lock-guarded (scopes may run off the main thread).
 *  4. Reverts run outside the lock (a revert may register a late revert; contract 2 drains it).
 *  5. [dispose] is not a completion barrier for in-flight suspend-acquires.
 */
class EffectScope(
    private val onRevertFailure: (Throwable) -> Unit,
) : Disposable {
    private val lock = Any()
    private val reverts = ArrayDeque<() -> Unit>()
    private var disposed = false

    fun onRevert(revert: () -> Unit): Disposable {
        val late = synchronized(lock) {
            if (disposed) true else { reverts.addLast(revert); false }
        }
        if (late) { runRevert(revert); return Disposable { } }
        return Disposable {
            val mine = synchronized(lock) { reverts.remove(revert) }
            if (mine) runRevert(revert)
        }
    }

    fun adopt(handle: Disposable): Disposable = onRevert(handle::dispose)

    fun onCompensate(compensate: () -> Unit): Disposable = onRevert(compensate)

    override fun dispose() {
        while (true) {
            val next = synchronized(lock) {
                disposed = true
                reverts.removeLastOrNull()
            } ?: return
            runRevert(next)
        }
    }

    private fun runRevert(revert: () -> Unit) {
        try { revert() } catch (t: Throwable) { onRevertFailure(t) }
    }
}
