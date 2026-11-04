package com.nuvio.tv.ui.screens.player

/**
 * What the next-episode auto-play should do with the streams seen so far.
 * TV twin of the KMP coordinator (upstream Desktop 8d8ee10f6, fixes #319).
 */
internal sealed interface NextEpisodeStreamSelectionDecision<out S> {
    data class Selected<S>(val stream: S) : NextEpisodeStreamSelectionDecision<S>
    data object Waiting : NextEpisodeStreamSelectionDecision<Nothing>
    data object ManualSelection : NextEpisodeStreamSelectionDecision<Nothing>
}

/**
 * Decides when the next-episode search picks a stream, waits, or hands over to manual selection.
 *
 * Before the selection delay only [selectPreferred] applies (the binge-group continuation); once the
 * delay has elapsed — or every addon has answered — the full [selectAfterDelay] picker applies.
 * The delay running out while addons are still answering is NOT a reason to give up: the matching
 * addon may simply be slower than an unrelated one. Only a finished search without a match is.
 *
 * [D] is the result set seen so far (TV: the addon groups), [S] the stream type.
 */
internal class NextEpisodeStreamSelectionCoordinator<D, S>(
    private val selectAfterDelay: (D) -> S?,
    private val selectPreferred: (D) -> S?,
) {
    private var selectionDelayElapsed = false

    fun onStreamsChanged(data: D?, searchComplete: Boolean): NextEpisodeStreamSelectionDecision<S> =
        decide(data, searchComplete)

    fun onSelectionDelayElapsed(data: D?, searchComplete: Boolean): NextEpisodeStreamSelectionDecision<S> {
        selectionDelayElapsed = true
        return decide(data, searchComplete)
    }

    private fun decide(data: D?, searchComplete: Boolean): NextEpisodeStreamSelectionDecision<S> {
        val selected = data?.let {
            if (selectionDelayElapsed || searchComplete) selectAfterDelay(it) else selectPreferred(it)
        }
        return when {
            selected != null -> NextEpisodeStreamSelectionDecision.Selected(selected)
            !searchComplete -> NextEpisodeStreamSelectionDecision.Waiting
            else -> NextEpisodeStreamSelectionDecision.ManualSelection
        }
    }
}
