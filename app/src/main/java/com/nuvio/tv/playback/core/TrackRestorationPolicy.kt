package com.nuvio.tv.playback.core

/**
 * The ONE fuzzy scorer deciding whether a user's audio/subtitle choice survives an engine
 * handoff or graph rebuild (house pattern: pure policy object). Both engine backends consume
 * this; a re-weighting tuned against one engine must never silently diverge the other's
 * restoration behavior.
 */
object TrackRestorationPolicy {
    fun score(candidate: PlaybackTrackDescriptor, selection: RestorableTrackSelection): Int =
        (if (candidate.language == selection.language && selection.language != null) 16 else 0) +
            (if (candidate.label == selection.label && selection.label != null) 8 else 0) +
            (if (candidate.mimeType == selection.mimeType && selection.mimeType != null) 4 else 0) +
            (if (candidate.codec == selection.codec && selection.codec != null) 2 else 0) +
            (if (candidate.channelCount == selection.channelCount && selection.channelCount != null) 1 else 0)

    /** Best positive-scoring candidate, or null when nothing plausibly matches. */
    fun <T> bestMatch(
        candidates: Iterable<T>,
        selection: RestorableTrackSelection,
        descriptor: (T) -> PlaybackTrackDescriptor,
    ): T? = candidates
        .asSequence()
        .map { it to score(descriptor(it), selection) }
        .filter { (_, value) -> value > 0 }
        .maxByOrNull { (_, value) -> value }
        ?.first
}

/**
 * The ONE content frame-rate validity band. Producers (both engine backends) and the consumer
 * (the AFR display selector) must agree on what counts as a factual rate, or a rate one side
 * admits is silently dropped by the other. Widen it here or nowhere.
 */
object ContentFrameRatePolicy {
    const val MIN_VALID_FPS = 10f
    const val MAX_VALID_FPS = 120f

    fun validOrNull(frameRate: Float): Float? =
        frameRate.takeIf { it.isFinite() && it in MIN_VALID_FPS..MAX_VALID_FPS }
}
