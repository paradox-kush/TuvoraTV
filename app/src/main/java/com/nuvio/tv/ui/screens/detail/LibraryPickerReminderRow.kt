package com.nuvio.tv.ui.screens.detail

/**
 * The "Remind me of new episodes" row inside the + (lists) picker. The calendar bell used to be a
 * standalone hero button; it now lives in the picker that long-press on + opens.
 *
 * [bellOn] is null for titles the Calendar cannot follow (no bell key) — then there is no row at all.
 * Otherwise the row shows the current state and a tap flips it ([nextState]); the toggle applies
 * immediately (it is not part of the picker's Save batch), exactly as the old bell did.
 */
internal data class LibraryPickerReminderRow(val isOn: Boolean) {
    val nextState: Boolean get() = !isOn

    companion object {
        fun forBell(bellOn: Boolean?): LibraryPickerReminderRow? = bellOn?.let { LibraryPickerReminderRow(it) }
    }
}
