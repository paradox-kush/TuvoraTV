package com.nuvio.tv.playback.wiring

import android.content.SharedPreferences
import com.nuvio.tv.playback.core.AudioCodec
import com.nuvio.tv.playback.core.CompatibilityOutcome
import com.nuvio.tv.playback.core.CompatibilityGraphFingerprint
import com.nuvio.tv.playback.core.CompatibilityRecord
import com.nuvio.tv.playback.core.CompatibilityRuntimeFingerprint
import com.nuvio.tv.playback.core.CompatibilityScopeKey
import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.PlaybackClock
import com.nuvio.tv.playback.core.PlaybackCompatibilityHistory
import com.nuvio.tv.playback.core.VideoCodec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface PlaybackCompatibilityStorage {
    fun read(): String?

    fun write(value: String)
}

class SharedPreferencesPlaybackCompatibilityStorage(
    private val preferences: SharedPreferences,
    private val key: String = "clean_playback_compatibility_v2",
) : PlaybackCompatibilityStorage {
    override fun read(): String? = preferences.getString(key, null)

    override fun write(value: String) {
        preferences.edit().putString(key, value).apply()
    }
}

/**
 * Exact, versioned, expiring compatibility history. It stores deterministic engine/render outcomes;
 * transport, authorization, and TLS failures are deliberately excluded from learning.
 */
class PersistentPlaybackCompatibilityHistory(
    private val storage: PlaybackCompatibilityStorage,
    private val clock: PlaybackClock,
    private val currentAppVersion: String,
    private val currentRuntime: CompatibilityRuntimeFingerprint,
    private val currentEngineVersions: Map<EngineType, String>,
    private val maxRecords: Int = DEFAULT_MAX_RECORDS,
    private val maxEncodedBytes: Int = DEFAULT_MAX_ENCODED_BYTES,
) : PlaybackCompatibilityHistory {
    private val mutex = Mutex()

    init {
        require(maxRecords > 0) { "Compatibility history record cap must be positive" }
        require(maxEncodedBytes > FORMAT_VERSION.length + 1) {
            "Compatibility history byte cap is too small"
        }
    }

    override suspend fun records(scopeKey: CompatibilityScopeKey): List<CompatibilityRecord> = mutex.withLock {
        val now = clock.nowEpochMs()
        val decoded = decode(storage.read())
        val retained = compact(decoded, now).map { stored ->
            if (stored.record.scopeKey == scopeKey && matchesCurrentRuntime(stored.record)) {
                stored.copy(lastAccessedAtEpochMs = now)
            } else {
                stored
            }
        }
        val compacted = compact(retained, now)
        if (compacted != decoded) storage.write(encode(compacted))
        compacted.asSequence()
            .filter { it.record.scopeKey == scopeKey }
            .filter { matchesCurrentRuntime(it.record) }
            .map(StoredRecord::record)
            .sortedByDescending(CompatibilityRecord::recordedAtEpochMs)
            .toList()
    }

    override suspend fun record(value: CompatibilityRecord) = mutex.withLock {
        if (!isLearnable(value)) return@withLock
        val now = clock.nowEpochMs()
        val existing = compact(decode(storage.read()), now).toMutableList()
        val sameGraph: (StoredRecord) -> Boolean = {
            it.record.scopeKey == value.scopeKey &&
                it.record.graph == value.graph &&
                it.record.runtime == value.runtime &&
                it.record.appVersion == value.appVersion &&
                it.record.engineVersion == value.engineVersion
        }
        // A verified success invalidates prior fatal history for the exact graph. A newer fatal
        // likewise replaces stale success; history never votes with contradictory simultaneous rows.
        existing.removeAll(sameGraph)
        existing += StoredRecord(value, lastAccessedAtEpochMs = now)
        storage.write(encode(compact(existing, now)))
    }

    private fun isLearnable(record: CompatibilityRecord): Boolean {
        if (record.appVersion != currentAppVersion) return false
        if (record.runtime != currentRuntime) return false
        if (currentEngineVersions[record.engine] != record.engineVersion) return false
        if (record.outcome == CompatibilityOutcome.SUCCESS) return true
        if (!record.deterministicFailure()) return false
        return record.failureDomain !in NON_ENGINE_FAILURE_DOMAINS &&
            record.failureCode !in NON_ENGINE_FAILURE_CODES
    }

    private fun matchesCurrentRuntime(record: CompatibilityRecord): Boolean =
        record.runtime == currentRuntime &&
            record.appVersion == currentAppVersion &&
            currentEngineVersions[record.engine] == record.engineVersion

    private fun CompatibilityRecord.deterministicFailure(): Boolean =
        outcome == CompatibilityOutcome.DETERMINISTIC_FATAL && failureDomain != null && failureCode != null

    private fun encode(records: List<StoredRecord>): String = buildString {
        appendLine(FORMAT_VERSION)
        records.forEach { stored ->
            val record = stored.record
            append(
                listOf(
                    encoded(record.scopeKey.value),
                    record.engine.name,
                    record.outputProfile.name,
                    record.graph.decoderMode.name,
                    record.graph.audioMode.name,
                    record.graph.surfaceMode.name,
                    record.graph.secureOutput.toString(),
                    encoded(record.graph.decoderStableId.orEmpty()),
                    record.outcome.name,
                    record.failureDomain?.name.orEmpty(),
                    record.failureCode?.name.orEmpty(),
                    encoded(record.appVersion),
                    encoded(record.engineVersion),
                    encoded(record.runtime.deviceVersion),
                    encoded(record.runtime.firmwareVersion),
                    encoded(record.runtime.capabilityFingerprint),
                    record.recordedAtEpochMs.toString(),
                    record.expiresAtEpochMs.toString(),
                    stored.lastAccessedAtEpochMs.toString(),
                ).joinToString("|"),
            )
            append('\n')
        }
    }

    private fun decode(value: String?): List<StoredRecord> {
        val lines = value?.lineSequence()?.toList().orEmpty()
        if (lines.firstOrNull() != FORMAT_VERSION) return emptyList()
        return lines.drop(1).mapNotNull(::decodeLine)
    }

    private fun decodeLine(line: String): StoredRecord? = runCatching {
        val parts = line.split('|')
        if (parts.size != FIELD_COUNT) return null
        StoredRecord(
            record = CompatibilityRecord(
                scopeKey = CompatibilityScopeKey(decoded(parts[0])),
                graph = CompatibilityGraphFingerprint(
                    engine = enumValueOf(parts[1]),
                    outputProfile = enumValueOf(parts[2]),
                    decoderMode = enumValueOf(parts[3]),
                    audioMode = enumValueOf(parts[4]),
                    surfaceMode = enumValueOf(parts[5]),
                    secureOutput = parts[6].toBooleanStrict(),
                    decoderStableId = decoded(parts[7]).ifBlank { null },
                ),
                outcome = enumValueOf(parts[8]),
                failureDomain = parts[9].takeIf(String::isNotEmpty)?.let(::enumValueOf),
                failureCode = parts[10].takeIf(String::isNotEmpty)?.let(::enumValueOf),
                appVersion = decoded(parts[11]),
                engineVersion = decoded(parts[12]),
                runtime = CompatibilityRuntimeFingerprint(
                    deviceVersion = decoded(parts[13]),
                    firmwareVersion = decoded(parts[14]),
                    capabilityFingerprint = decoded(parts[15]),
                ),
                recordedAtEpochMs = parts[16].toLong(),
                expiresAtEpochMs = parts[17].toLong(),
            ),
            lastAccessedAtEpochMs = parts[18].toLong(),
        )
    }.getOrNull()

    private fun encoded(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decoded(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    private data class StoredRecord(
        val record: CompatibilityRecord,
        val lastAccessedAtEpochMs: Long,
    )

    private fun compact(records: List<StoredRecord>, nowEpochMs: Long): List<StoredRecord> {
        val retained = records
            .asSequence()
            .filterNot { it.record.isExpired(nowEpochMs) }
            .sortedWith(
                compareByDescending<StoredRecord> { it.lastAccessedAtEpochMs }
                    .thenByDescending { it.record.recordedAtEpochMs },
            )
            .take(maxRecords)
            .toMutableList()
        while (retained.isNotEmpty() && encode(retained).toByteArray(StandardCharsets.UTF_8).size > maxEncodedBytes) {
            retained.removeAt(retained.lastIndex)
        }
        return retained
    }

    private companion object {
        const val FORMAT_VERSION = "nuvio-playback-history-v2"
        const val FIELD_COUNT = 19
        const val DEFAULT_MAX_RECORDS = 512
        const val DEFAULT_MAX_ENCODED_BYTES = 256 * 1024
        val NON_ENGINE_FAILURE_DOMAINS = setOf(
            FailureDomain.NETWORK,
            FailureDomain.AUTHORIZATION_PROVIDER_LIMIT,
            FailureDomain.TLS,
        )
        val NON_ENGINE_FAILURE_CODES = setOf(
            FailureCode.NETWORK_UNREACHABLE,
            FailureCode.NETWORK_TIMEOUT,
            FailureCode.AUTHORIZATION_REJECTED,
            FailureCode.PROVIDER_CONNECTION_LIMIT,
            FailureCode.TLS_HANDSHAKE_FAILED,
        )
    }
}

/** Secret-bearing exact scope input. Only its SHA-256 digest is persisted. */
class CompatibilityScopeInput(
    val providerScope: String,
    val streamScope: String,
    val contentType: ContentType,
    val delivery: DeliveryType?,
    val container: ContainerType?,
    val videoCodec: VideoCodec?,
    val audioCodec: AudioCodec?,
) {
    init {
        require(providerScope.isNotBlank()) { "Provider scope must not be blank" }
        require(streamScope.isNotBlank()) { "Stream scope must not be blank" }
    }

    override fun toString(): String =
        "CompatibilityScopeInput(contentType=$contentType, delivery=$delivery, container=$container)"
}

object CompatibilityScopeKeyFactory {
    fun create(input: CompatibilityScopeInput): CompatibilityScopeKey {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            input.providerScope,
            input.streamScope,
            input.contentType.name,
            input.delivery?.name.orEmpty(),
            input.container?.name.orEmpty(),
            input.videoCodec?.name.orEmpty(),
            input.audioCodec?.name.orEmpty(),
        ).forEach { component ->
            val bytes = component.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        return CompatibilityScopeKey(digest.digest().joinToString("") { "%02x".format(it) })
    }
}
