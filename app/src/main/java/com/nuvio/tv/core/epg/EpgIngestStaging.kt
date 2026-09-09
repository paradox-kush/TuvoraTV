package com.nuvio.tv.core.epg

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File

/**
 * One staged channel row (NDJSON on disk): its source's stable ORDINAL (so a later/duplicate slug
 * cannot mis-attribute it), the epg id, and one display name. JSON escapes any tab/newline in a name.
 * Twin of the KMP EpgStagedRow.
 */
@Serializable
internal data class EpgStagedRow(val o: Int, val i: String, val n: String)

private val stagingJson = Json { ignoreUnknownKeys = true }
internal fun encodeStagedRow(row: EpgStagedRow): String = stagingJson.encodeToString(EpgStagedRow.serializer(), row)
internal fun decodeStagedRow(line: String): EpgStagedRow = stagingJson.decodeFromString(EpgStagedRow.serializer(), line)

/**
 * Bounded, off-DB, off-heap staging for one channels-index ingest — a unique temp file.
 *
 * PHASE 1 streams the network here (no EPG database write, so no lock is held while awaiting the
 * network); PHASE 2 reads it back and promotes only the kept sources into the shadow. The whole
 * document, a whole source's channel list, and the whole catalog all stay out of memory. Twin of the
 * KMP EpgIngestStaging (TV's phase 2 runs on its sync EPG dispatcher, so a plain [forEachLine] read
 * is enough — no pull cursor needed).
 */
internal class EpgIngestStaging {
    private val file: File = File.createTempFile("epg-index-stage-", ".ndjson").apply { deleteOnExit() }
    private var writer: BufferedWriter? = file.bufferedWriter()

    fun append(line: String) {
        writer?.apply { write(line); newLine() }
    }

    fun finishWriting() {
        writer?.let { runCatching { it.flush(); it.close() } }
        writer = null
    }

    fun forEachLine(block: (String) -> Unit) {
        file.bufferedReader().use { r -> r.forEachLine(block) }
    }

    fun dispose() {
        runCatching { writer?.close() }
        writer = null
        runCatching { file.delete() }
    }
}
