package com.nuvio.tv.core.epg

/**
 * The guide-region picker's selection rules.
 *
 * Storage keeps "no preference" as the EMPTY set (every region — the opt-in default an untouched
 * install has). The picker used to draw that state as every row UNCHECKED, so one OK on a region
 * turned "all 23" into "just this one" and Done dropped the other 22 regions' guide data (B119).
 * Here the empty set reads as every row checked, OK on a row under "All" removes that one region,
 * and a selection that grows back to every region collapses to "All" again.
 *
 * Pure so the twins (phone, desktop, Apple TV) share one set of rules and tests.
 */
object EpgRegionSelection {

    /** Whether [name]'s row draws checked: under "All" (empty) every row is. */
    fun isChecked(selected: Set<String>, name: String): Boolean = selected.isEmpty() || name in selected

    /**
     * The selection after OK on [name], given every region the picker shows ([all]).
     *
     * The last checked region cannot be unchecked: an empty result would silently mean "All",
     * the opposite of what that press asked for.
     */
    fun toggle(selected: Set<String>, all: List<String>, name: String): Set<String> {
        val visible = all.toSet()
        // Names the catalog no longer publishes are not regions the viewer can see or choose.
        val effective = if (selected.isEmpty()) visible else selected.intersect(visible)
        val next = if (name in effective) effective - name else effective + name
        return when {
            next.isEmpty() -> effective
            next.containsAll(visible) -> emptySet()
            else -> next
        }
    }
}
