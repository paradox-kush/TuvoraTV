package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.Recording
import com.nuvio.tv.core.recordings.api.RecordingSchedule
import com.nuvio.tv.core.recordings.api.SeriesRule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One persisted row: the typed [value] plus the [raw] JSON it was read from. [value] is null when
 * the row did not decode (a newer app's enum value, a missing field): the row is then kept verbatim
 * and written back, never dropped. A row an older app rewrites keeps every field it does not know.
 *
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0) for the shapes; the lossless round trip is ours.
 */
data class StoredRow<T>(val value: T?, val raw: JsonElement = JsonObject(emptyMap())) {
    /** The same row with an updated value; unknown fields in [raw] ride along. */
    fun withValue(updated: T): StoredRow<T> = StoredRow(updated, raw)

    companion object {
        fun <T> of(value: T): StoredRow<T> = StoredRow(value)
    }
}

/**
 * The whole recordings file. [schemaVersion] is what the file said (missing = legacy = 1);
 * [extra] holds top-level keys this version does not know.
 */
data class RecordingsDocument(
    val schemaVersion: Int = RecordingsCodec.SCHEMA_VERSION,
    val schedules: List<StoredRow<RecordingSchedule>> = emptyList(),
    val series: List<StoredRow<SeriesRule>> = emptyList(),
    val recordings: List<StoredRow<Recording>> = emptyList(),
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    val scheduleValues: List<RecordingSchedule> get() = schedules.mapNotNull { it.value }
    val seriesValues: List<SeriesRule> get() = series.mapNotNull { it.value }
    val recordingValues: List<Recording> get() = recordings.mapNotNull { it.value }
}

/**
 * Re-bases [this] on [updated]: a value whose id is already stored keeps its raw (unknown fields),
 * a new id gets a fresh row, and rows that never decoded stay at the end untouched.
 */
fun <T> List<StoredRow<T>>.reconcile(updated: List<T>, idOf: (T) -> String): List<StoredRow<T>> = TODO()

object RecordingsCodec {
    const val SCHEMA_VERSION = 1
    fun decode(text: String?): RecordingsDocument? = TODO()
    fun encode(doc: RecordingsDocument): String = TODO()
}
