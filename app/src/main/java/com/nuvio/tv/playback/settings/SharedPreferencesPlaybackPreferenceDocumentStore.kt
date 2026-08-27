package com.nuvio.tv.playback.settings

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated production storage for the clean player. One encoded document is committed atomically
 * per profile; this file is intentionally distinct from every legacy settings file.
 */
class SharedPreferencesPlaybackPreferenceDocumentStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : PlaybackPreferenceDocumentStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    override suspend fun read(profileId: String): PlaybackPreferenceDocument? =
        preferences.getString(documentKey(profileId), null)?.let(PlaybackPreferenceDocumentSerializer::deserialize)

    override suspend fun write(profileId: String, document: PlaybackPreferenceDocument) = withContext(Dispatchers.IO) {
        val persisted = preferences.edit()
            .putString(documentKey(profileId), PlaybackPreferenceDocumentSerializer.serialize(document))
            .commit()
        check(persisted) { "Unable to persist clean playback preferences" }
    }

    private fun documentKey(profileId: String): String {
        require(profileId.isNotBlank()) { "Playback preference profile id must not be blank" }
        return "$DOCUMENT_PREFIX${PlaybackPreferenceDocumentSerializer.encodeText(profileId)}"
    }

    companion object {
        const val DEFAULT_PREFERENCES_NAME: String = "clean_playback_preferences_v1"
        private const val DOCUMENT_PREFIX: String = "profile."
    }
}

/** Strict, deterministic and dependency-free serializer for [PlaybackPreferenceDocument]. */
object PlaybackPreferenceDocumentSerializer {
    private const val MAGIC = "NTP1"
    private const val NULL = "-"
    private val hex = "0123456789abcdef".toCharArray()

    fun serialize(document: PlaybackPreferenceDocument): String = buildString {
        appendLine(MAGIC)
        appendLine(document.schemaVersion)
        appendLine(document.revision)
        appendLine(document.legacyImportToken?.let(::encodeText) ?: NULL)
        appendMap(document.values)
        appendMap(document.preservedUnknownValues)
    }

    fun deserialize(encoded: String): PlaybackPreferenceDocument {
        val lines = encoded.lineSequence().iterator()
        fun next(label: String): String {
            require(lines.hasNext()) { "Playback preference document is missing $label" }
            return lines.next()
        }

        require(next("magic") == MAGIC) { "Unsupported playback preference document encoding" }
        val schemaVersion = next("schema version").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid playback preference schema version")
        val revision = next("revision").toLongOrNull()
            ?: throw IllegalArgumentException("Invalid playback preference revision")
        val token = next("legacy import token").let { if (it == NULL) null else decodeText(it) }
        val values = readMap(lines, "values")
        val unknown = readMap(lines, "preserved unknown values")
        while (lines.hasNext()) {
            require(lines.next().isEmpty()) { "Unexpected trailing playback preference data" }
        }
        return PlaybackPreferenceDocument(
            schemaVersion = schemaVersion,
            revision = revision,
            values = values,
            preservedUnknownValues = unknown,
            legacyImportToken = token,
        )
    }

    internal fun encodeText(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return CharArray(bytes.size * 2).also { chars ->
            bytes.forEachIndexed { index, byte ->
                val unsigned = byte.toInt() and 0xff
                chars[index * 2] = hex[unsigned ushr 4]
                chars[index * 2 + 1] = hex[unsigned and 0x0f]
            }
        }.concatToString()
    }

    private fun decodeText(value: String): String {
        require(value.length % 2 == 0) { "Invalid encoded playback preference text" }
        val bytes = ByteArray(value.length / 2)
        bytes.indices.forEach { index ->
            val high = value[index * 2].digitToIntOrNull(16)
            val low = value[index * 2 + 1].digitToIntOrNull(16)
            require(high != null && low != null) { "Invalid encoded playback preference text" }
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun StringBuilder.appendMap(values: Map<String, String>) {
        appendLine(values.size)
        values.toSortedMap().forEach { (key, value) ->
            append(encodeText(key))
            append('=')
            appendLine(encodeText(value))
        }
    }

    private fun readMap(lines: Iterator<String>, label: String): Map<String, String> {
        require(lines.hasNext()) { "Playback preference document is missing $label count" }
        val count = lines.next().toIntOrNull()
            ?: throw IllegalArgumentException("Invalid playback preference $label count")
        require(count >= 0) { "Invalid playback preference $label count" }
        val result = linkedMapOf<String, String>()
        repeat(count) {
            require(lines.hasNext()) { "Playback preference document is missing a $label entry" }
            val line = lines.next()
            val separator = line.indexOf('=')
            require(separator >= 0) { "Invalid playback preference $label entry" }
            val key = decodeText(line.substring(0, separator))
            val value = decodeText(line.substring(separator + 1))
            require(result.put(key, value) == null) { "Duplicate playback preference key" }
        }
        return result.toMap()
    }
}
