package com.nuvio.tv.ui.screens.player

internal enum class ProgressSaveAction { MARK_COMPLETED, SAVE_PROGRESS, SKIP }

/**
 * What a player progress save should do, given whether this save reads as completed and whether
 * the current episode was already marked completed during this playback session.
 *
 * Once an episode is marked completed, later saves in the same session are skipped. After natural
 * completion the player can report stale position/duration (e.g. duration=0, which falls back to a
 * 5% progress value); saving that would overwrite the completed entry and push a low-progress value
 * to remote. The flag is reset when a new item is loaded, so the next episode saves normally.
 */
internal object CompletedEpisodeSavePolicy {
    fun decide(isCompleted: Boolean, alreadyMarkedCompleted: Boolean): ProgressSaveAction = when {
        alreadyMarkedCompleted -> ProgressSaveAction.SKIP
        isCompleted -> ProgressSaveAction.MARK_COMPLETED
        else -> ProgressSaveAction.SAVE_PROGRESS
    }
}
