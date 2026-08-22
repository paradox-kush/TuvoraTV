package com.nuvio.tv.arch

/**
 * Frozen crossing surface for NuvioTV as of the firewall port (2026-08-19). Generated from the exact
 * rule in [ArchitectureTest], NOT hand-listed. The ratchet: this set only SHRINKS — each de-fork step
 * removes its files as it moves the reference behind a contract or a Hilt-bound interface. A change
 * that adds a NEW crossing goes red. Do not add entries to silence a rule; fix the crossing.
 *
 * 19 pre-existing crossings (22 − 3 drained by the Part B live-playback seam) — the coupling the KMP repos already drained to zero, now visible
 * and ratcheted on TV. Draining these is follow-on seam work.
 */
object ArchBaseline {
    val crossings: Set<String> = setOf(
        "com/nuvio/tv/core/auth/AccountLocalDataResetService.kt",
        "com/nuvio/tv/core/sync/StartupSyncService.kt",
        "com/nuvio/tv/core/sync/WatchProgressSyncService.kt",
        "com/nuvio/tv/core/sync/XtreamAccountSyncService.kt",
        "com/nuvio/tv/data/local/XtreamAccountStore.kt",
        "com/nuvio/tv/data/repository/MetaRepositoryImpl.kt",
        "com/nuvio/tv/data/repository/StreamRepositoryImpl.kt",
        "com/nuvio/tv/data/repository/WatchProgressRepositoryImpl.kt",
        "com/nuvio/tv/ui/navigation/NuvioNavHost.kt",
        "com/nuvio/tv/ui/screens/account/AccountViewModel.kt",
        "com/nuvio/tv/ui/screens/home/HomeViewModelContinueWatching.kt",
        "com/nuvio/tv/ui/screens/home/ModernHomeRows.kt",
        "com/nuvio/tv/ui/screens/player/NuvioExoPlayerPerformanceHelper.kt",
        "com/nuvio/tv/ui/screens/player/NuvioMpvSurfaceView.kt",
        "com/nuvio/tv/ui/screens/settings/AdvancedSettingsViewModel.kt",
        "com/nuvio/tv/ui/screens/settings/DebugSettingsViewModel.kt",
        "com/nuvio/tv/ui/screens/settings/XtreamSettingsScreen.kt",
        "com/nuvio/tv/ui/screens/settings/XtreamSettingsViewModel.kt",
        "com/nuvio/tv/ui/screens/stream/StreamScreenViewModel.kt",
    )
}
