package com.nuvio.tv.core.contracts

import java.util.concurrent.atomic.AtomicReference

/**
 * Registration-ordered, copy-on-write registry that REFUSES duplicate names - the precedent set by the
 * KMP repos' `NamedRegistry` (Mobile P0, media-servers design 5.1), shared by every plural source port
 * (stream, meta, search, own-source predicates, home sections, session reporters).
 *
 * Registration is PROCESS-INIT state (the Application / Hilt composition root), so a duplicate is a wiring
 * bug and fails loudly at startup rather than silently shadowing a source. Reads are lock-free and
 * allocation-free: every registration publishes a new immutable snapshot.
 */
class NamedRegistry<T : Any>(private val kind: String) {
    private class Snapshot<T>(val names: List<String>, val items: List<T>)

    private val snapshot = AtomicReference(Snapshot<T>(emptyList(), emptyList()))

    fun register(name: String, item: T) {
        require(name.isNotBlank()) { "$kind name must not be blank" }
        while (true) {
            val current = snapshot.get()
            require(name !in current.names) { "duplicate $kind: $name" }
            val next = Snapshot(current.names + name, current.items + item)
            if (snapshot.compareAndSet(current, next)) return
        }
    }

    /** Entries in registration order. */
    val all: List<T> get() = snapshot.get().items

    val names: List<String> get() = snapshot.get().names

    val isEmpty: Boolean get() = snapshot.get().items.isEmpty()

    /** Tests put the process back as they found it. */
    fun resetForTest() {
        snapshot.set(Snapshot(emptyList(), emptyList()))
    }
}
