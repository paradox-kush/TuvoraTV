package com.nuvio.tv.core.radar

import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Games the viewer chose to reveal while "Hide scores" is on. Session-only: in memory, never persisted or synced,
 * gone on app restart and on a profile switch. Keyed by game id ([RadarCalendarGames.gameId]).
 */
@Singleton
class RevealedGames(private val activeProfile: () -> Int) {
    @Inject constructor(profileManager: ProfileManager) : this({ profileManager.activeProfileId.value })

    private val state = MutableStateFlow<Set<String>>(emptySet())
    private var profile = activeProfile()

    val ids: StateFlow<Set<String>> = state.asStateFlow()

    /** The revealed ids for the active profile (a profile switch forgets them). */
    @Synchronized
    fun current(): Set<String> { syncProfile(); return state.value }

    @Synchronized fun reveal(id: String) { syncProfile(); state.value = state.value + id }

    @Synchronized fun hideAgain(id: String) { syncProfile(); state.value = state.value - id }

    private fun syncProfile() {
        val now = activeProfile()
        if (now != profile) { profile = now; state.value = emptySet() }
    }
}
