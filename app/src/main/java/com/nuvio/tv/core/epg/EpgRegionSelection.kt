package com.nuvio.tv.core.epg

/** STUB (red step): today's picker behaviour. */
object EpgRegionSelection {
    fun isChecked(selected: Set<String>, name: String): Boolean = name in selected
    fun toggle(selected: Set<String>, all: List<String>, name: String): Set<String> =
        if (name in selected) selected - name else selected + name
}
