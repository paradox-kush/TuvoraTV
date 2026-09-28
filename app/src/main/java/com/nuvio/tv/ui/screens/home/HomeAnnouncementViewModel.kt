package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.AnnouncementRepository
import com.nuvio.tv.domain.model.Announcement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State holder for the dismissible announcement card at the top of Home. */
@HiltViewModel
class HomeAnnouncementViewModel @Inject constructor(
    private val repository: AnnouncementRepository,
) : ViewModel() {

    // Hides a dismissed card on the same frame, before the DataStore write round-trips.
    private val locallyDismissed = MutableStateFlow<Set<String>>(emptySet())

    val announcement: StateFlow<Announcement?> =
        combine(repository.current, locallyDismissed) { current, dismissed ->
            current?.takeIf { it.id !in dismissed }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Called on Home ON_RESUME; the repository decides whether a request is actually due. */
    fun onHomeResumed() {
        viewModelScope.launch { repository.refreshIfDue() }
    }

    fun dismiss(id: String) {
        locallyDismissed.update { it + id }
        viewModelScope.launch { repository.dismiss(id) }
    }
}
