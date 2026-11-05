package com.nuvio.tv.core.recordings.internal

/** The one formula for a series rule's id, so the repository that saves a rule and the UI that looks for it agree. */
object SeriesRuleIds {
    fun of(channelKey: String, title: String): String = "series:$channelKey:${SeriesExpansion.normalizeTitle(title)}"
}
