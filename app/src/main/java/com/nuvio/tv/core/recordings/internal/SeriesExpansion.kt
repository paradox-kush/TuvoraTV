package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.Recording
import com.nuvio.tv.core.recordings.api.RecordingSchedule
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.SeriesRule

/** One programme from the guide, as a series rule sees it. [episodeText] is the guide's own episode field (xmltv_ns or on-screen). */
data class ListingInfo(
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    val episodeText: String? = null,
)

/**
 * "Record series": turns a rule plus the guide's listings into schedules. Pure and idempotent:
 * a schedule's id is `"${rule.id}:${channel.key}:${start}"`, so running it again over the same
 * guide adds nothing, and repeats of an episode already scheduled or recorded are skipped.
 *
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), `LiveTvRecordingScheduler.expandSeries`.
 */
object SeriesExpansion {

    fun scheduleId(rule: SeriesRule, startMs: Long): String = "${rule.id}:${rule.channel.key}:$startMs"

    /** The title with case, punctuation and NEW/LIVE marks folded away, to compare programmes by name. */
    fun normalizeTitle(title: String): String {
        var t = title.lowercase()
        t = t.replace(Regex("""[\[(]\s*(new|live|r|repeat|rpt)\s*[\])]"""), " ")
        t = t.replace(Regex("""[ᴺᴱᵂᴸᴵⱽᴬᴴ]"""), "") // superscript N E W L I V
        t = t.replace(Regex("""\s*[-|:/]\s*(new|live)\s*$"""), " ")
        t = t.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        return t.trim()
    }

    /**
     * @param modeFor how a listing would be captured here (see `RecordingSchedulePolicy.modeFor`),
     *   or null when it cannot be.
     */
    fun expand(
        rule: SeriesRule,
        listings: List<ListingInfo>,
        existingSchedules: List<RecordingSchedule>,
        existingRecordings: List<Recording>,
        nowMs: Long,
        preMs: Long,
        postMs: Long,
        modeFor: (startMs: Long, endMs: Long) -> CaptureMode?,
    ): List<RecordingSchedule> {
        val ruleTitle = normalizeTitle(rule.title)
        val channelKey = rule.channel.key

        // What this rule on this channel already has, as episode keys and as description text.
        val seenKeys = HashSet<String>()
        val seenDescriptions = HashSet<String>()
        fun note(title: String, episode: String?, description: String?) {
            SeriesEpisodeKey.fromProgramme(title, description, episode)?.let { seenKeys += it.normalized }
                ?: description?.let { d -> normalizeText(d).takeIf { it.isNotEmpty() }?.let { seenDescriptions += it } }
        }
        existingSchedules
            .filter { it.seriesRuleId == rule.id || (it.channel.key == channelKey && normalizeTitle(it.title) == ruleTitle) }
            .forEach { note(it.title, it.episode, it.description) }
        existingRecordings
            .filter {
                it.status in COUNTING &&
                    (it.seriesRuleId == rule.id || (it.channelKey == channelKey && normalizeTitle(it.title) == ruleTitle))
            }
            .forEach { note(it.title, it.episode, it.description) }

        val out = ArrayList<RecordingSchedule>()
        for (l in listings.sortedBy { it.startMs }) {
            if (normalizeTitle(l.title) != ruleTitle) continue
            val id = scheduleId(rule, l.startMs)
            if (existingSchedules.any { it.id == id || (it.channel.key == channelKey && it.programmeStartMs == l.startMs) }) continue
            if (existingRecordings.any { it.channelKey == channelKey && it.programmeStartMs == l.startMs && it.status in COUNTING }) continue
            val mode = modeFor(l.startMs, l.endMs) ?: continue
            if (mode == CaptureMode.LIVE && l.startMs <= nowMs) continue

            val key = SeriesEpisodeKey.fromProgramme(l.title, l.description, l.episodeText)
            if (key != null) {
                if (!seenKeys.add(key.normalized)) continue // a repeat
            } else {
                val d = l.description?.let(::normalizeText)?.takeIf { it.isNotEmpty() }
                if (d != null && !seenDescriptions.add(d)) continue
            }
            out += RecordingSchedule(
                id = id,
                channel = rule.channel,
                title = l.title.trim(),
                episode = key?.normalized,
                description = l.description,
                programmeStartMs = l.startMs,
                programmeEndMs = l.endMs,
                mode = mode,
                seriesRuleId = rule.id,
                preMs = preMs,
                postMs = postMs,
            )
        }
        return out
    }

    /** Ids of the recordings past the rule's "keep last N" (the newest by programme start are kept). Only finished ones are ever listed. */
    fun keepLastToDelete(rule: SeriesRule, recordings: List<Recording>): List<String> {
        if (rule.keepLast <= 0) return emptyList()
        return recordings
            .filter { it.seriesRuleId == rule.id && it.status == RecordingStatus.DONE }
            .sortedWith(compareByDescending<Recording> { it.programmeStartMs }.thenBy { it.id })
            .drop(rule.keepLast)
            .map { it.id }
    }

    private val COUNTING = setOf(RecordingStatus.SCHEDULED, RecordingStatus.RECORDING, RecordingStatus.DONE)

    private fun normalizeText(s: String): String = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
