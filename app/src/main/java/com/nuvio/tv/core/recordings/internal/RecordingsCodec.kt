package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.Recording
import com.nuvio.tv.core.recordings.api.RecordingSchedule
import com.nuvio.tv.core.recordings.api.SeriesRule
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject

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
    /** The fire ledger, folded into this document (v2) so state and ledger always commit together. */
    val ledger: List<StoredRow<FireEntry>> = emptyList(),
    val ledgerNextSeq: Long = 1,
    /** Keys of the `ledger` object this version does not know. */
    val ledgerExtra: JsonObject = JsonObject(emptyMap()),
    /** The schemaVersion the file said (missing = 1); [schemaVersion] is what gets written back. */
    val loadedVersion: Int = RecordingsCodec.SCHEMA_VERSION,
) {
    val ledgerState: FireLedgerState get() = FireLedgerState(ledger.mapNotNull { it.value }, ledgerNextSeq)

    /** This document with the ledger replaced by [state]; unknown fields of surviving entries ride along. */
    fun withLedger(state: FireLedgerState): RecordingsDocument =
        copy(ledger = ledger.reconcile(state.entries) { it.scheduleId }, ledgerNextSeq = state.nextSeq)

    val scheduleValues: List<RecordingSchedule> get() = schedules.mapNotNull { it.value }
    val seriesValues: List<SeriesRule> get() = series.mapNotNull { it.value }
    val recordingValues: List<Recording> get() = recordings.mapNotNull { it.value }
}

/**
 * Re-bases [this] on [updated]: a value whose id is already stored keeps its raw (unknown fields),
 * a new id gets a fresh row, and rows that never decoded stay at the end untouched.
 */
fun <T> List<StoredRow<T>>.reconcile(updated: List<T>, idOf: (T) -> String): List<StoredRow<T>> {
    val byId = HashMap<String, StoredRow<T>>()
    for (row in this) row.value?.let { byId[idOf(it)] = row }
    val rebuilt = updated.map { v -> byId[idOf(v)]?.withValue(v) ?: StoredRow.of(v) }
    return rebuilt + filter { it.value == null }
}

/**
 * `{ "schemaVersion": 1, "schedules": [...], "series": [...], "recordings": [...] }`, pure string
 * to model and back. Nothing a newer app wrote is lost when this version rewrites the file:
 * unknown fields (top level, per row, inside the channel) survive, a higher schemaVersion is kept,
 * and a row that cannot be decoded is re-emitted verbatim. The platform writer does tmp-file+rename.
 */
object RecordingsCodec {
    /** 2 folds the fire ledger into the document; 1 kept it in a second file. */
    const val SCHEMA_VERSION = 2

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    private val LEDGER_KNOWN = setOf("nextSeq", "entries")

    /** Nested model objects whose unknown fields are kept; maps (headers) are replaced whole. */
    private val mergeKeys = setOf("channel", "catchUp")

    /**
     * An empty document for null or blank text; null when the text is not a JSON object at all
     * (the caller keeps the file aside rather than overwrite it with an empty one).
     */
    fun decode(text: String?): RecordingsDocument? {
        if (text.isNullOrBlank()) return RecordingsDocument()
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 0
        val known = setOf("schemaVersion", "schedules", "series", "recordings", "ledger")
        val ledgerObj = root["ledger"] as? JsonObject
        return RecordingsDocument(
            schemaVersion = version.coerceAtLeast(SCHEMA_VERSION),
            schedules = rows(root["schedules"], RecordingSchedule.serializer()),
            series = rows(root["series"], SeriesRule.serializer()),
            recordings = rows(root["recordings"], Recording.serializer()),
            extra = JsonObject(root.filterKeys { it !in known }),
            ledger = rows(ledgerObj?.get("entries"), FireEntry.serializer()),
            ledgerNextSeq = (ledgerObj?.get("nextSeq") as? JsonPrimitive)?.longOrNull ?: 1L,
            ledgerExtra = JsonObject(ledgerObj?.filterKeys { it !in LEDGER_KNOWN } ?: emptyMap()),
            loadedVersion = version.coerceAtLeast(1),
        )
    }

    fun encode(doc: RecordingsDocument): String {
        val out = LinkedHashMap<String, JsonElement>(doc.extra)
        out["schemaVersion"] = JsonPrimitive(maxOf(doc.schemaVersion, SCHEMA_VERSION))
        out["schedules"] = JsonArray(doc.schedules.map { row(it, RecordingSchedule.serializer()) })
        out["series"] = JsonArray(doc.series.map { row(it, SeriesRule.serializer()) })
        out["recordings"] = JsonArray(doc.recordings.map { row(it, Recording.serializer()) })
        val ledger = LinkedHashMap<String, JsonElement>(doc.ledgerExtra)
        ledger["nextSeq"] = JsonPrimitive(doc.ledgerNextSeq)
        ledger["entries"] = JsonArray(doc.ledger.map { row(it, FireEntry.serializer()) })
        out["ledger"] = JsonObject(ledger)
        return json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    private fun <T> rows(element: JsonElement?, serializer: KSerializer<T>): List<StoredRow<T>> =
        (element as? JsonArray)?.map { el ->
            val value = try {
                json.decodeFromJsonElement(serializer, el)
            } catch (_: Exception) {
                null
            }
            StoredRow(value, el)
        } ?: emptyList()

    private fun <T> row(row: StoredRow<T>, serializer: KSerializer<T>): JsonElement {
        val value = row.value ?: return row.raw
        val known = json.encodeToJsonElement(serializer, value).jsonObject
        val raw = row.raw as? JsonObject ?: return known
        return overlay(raw, known)
    }

    private fun overlay(raw: JsonObject, known: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(raw)
        for ((key, v) in known) {
            val old = raw[key]
            out[key] = if (key in mergeKeys && old is JsonObject && v is JsonObject &&
                old["kind"] == v["kind"]
            ) overlay(old, v) else v
        }
        return JsonObject(out)
    }
}
