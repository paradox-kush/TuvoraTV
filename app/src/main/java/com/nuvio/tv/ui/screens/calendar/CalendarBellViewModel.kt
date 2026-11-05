package com.nuvio.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.calendar.CalendarRefreshReason
import com.nuvio.tv.core.calendar.CalendarRefresher
import com.nuvio.tv.core.calendar.CalendarSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The bell on a title page: adds the title to (or removes it from) the profile's reminder set. */
@HiltViewModel
class CalendarBellViewModel @Inject constructor(
    private val settingsStore: CalendarSettingsDataStore,
    private val refresher: CalendarRefresher
) : ViewModel() {
    val bells: StateFlow<Set<String>> = settingsStore.settings
        .map { it?.bellOn.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setBell(titleKey: String, on: Boolean) {
        viewModelScope.launch {
            settingsStore.setBell(titleKey, on)
            // A new bell title has no dates yet; a settings change always runs (only that title is due).
            if (on) refresher.requestRefresh(CalendarRefreshReason.SETTINGS_CHANGED)
        }
    }
}
