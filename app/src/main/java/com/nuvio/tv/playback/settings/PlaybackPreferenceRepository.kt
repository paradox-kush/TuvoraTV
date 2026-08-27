package com.nuvio.tv.playback.settings

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface PlaybackPreferenceDocumentStore {
    suspend fun read(profileId: String): PlaybackPreferenceDocument?
    suspend fun write(profileId: String, document: PlaybackPreferenceDocument)
}

data class PlaybackPreferenceSnapshot(
    val preferences: CleanPlaybackPreferences,
    val revision: Long,
    val warnings: Set<PreferenceDecodeWarning>,
    val preservedUnknownValues: Map<String, String>,
    val legacyImportToken: String?,
)

data class LegacyPreferenceImportResult(
    val imported: Boolean,
    val snapshot: PlaybackPreferenceSnapshot,
    val notes: Set<LegacyImportNote> = emptySet(),
)

/**
 * The clean repository owns only its isolated document store. It never reads or writes the legacy
 * DataStore; cutover code must pass an immutable legacy snapshot explicitly.
 */
class PlaybackPreferenceRepository(
    private val store: PlaybackPreferenceDocumentStore,
) {
    private val mutex = Mutex()

    suspend fun load(profileId: String): PlaybackPreferenceSnapshot = mutex.withLock {
        val stored = store.read(profileId) ?: return@withLock decode(PlaybackPreferenceSchema.newDocument())
        val decoded = PlaybackPreferenceSchema.decode(stored)
        if (stored.schemaVersion < CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION) {
            store.write(profileId, decoded.document)
        }
        snapshot(decoded)
    }

    suspend fun updateGroup(
        profileId: String,
        group: PlaybackPreferenceGroup,
        transform: (CleanPlaybackPreferences) -> CleanPlaybackPreferences,
    ): PlaybackPreferenceSnapshot = mutex.withLock {
        val currentDocument = store.read(profileId) ?: PlaybackPreferenceSchema.newDocument()
        checkWritable(currentDocument)
        val current = PlaybackPreferenceSchema.decode(currentDocument)
        val proposed = transform(current.preferences)
        val scoped = PlaybackPreferenceSchema.replaceGroup(current.preferences, proposed, group)
        val next = PlaybackPreferenceSchema.patchGroup(current.document, scoped, group)
        store.write(profileId, next)
        decode(next)
    }

    suspend fun resetGroup(
        profileId: String,
        group: PlaybackPreferenceGroup,
    ): PlaybackPreferenceSnapshot = mutex.withLock {
        val current = store.read(profileId) ?: PlaybackPreferenceSchema.newDocument()
        checkWritable(current)
        val decoded = PlaybackPreferenceSchema.decode(current)
        val next = PlaybackPreferenceSchema.resetGroup(decoded.document, group)
        store.write(profileId, next)
        decode(next)
    }

    /** Seeds only an absent clean store, making repeated cutover attempts deterministic no-ops. */
    suspend fun importLegacyIfAbsent(
        profileId: String,
        legacy: LegacyPlayerSettingsSnapshot,
    ): LegacyPreferenceImportResult = mutex.withLock {
        val existing = store.read(profileId)
        if (existing != null) {
            return@withLock LegacyPreferenceImportResult(imported = false, snapshot = decode(existing))
        }
        val mapped = LegacyPlaybackPreferenceImporter.map(legacy)
        val document = PlaybackPreferenceSchema.newDocument(mapped.preferences).copy(
            revision = 1,
            legacyImportToken = legacy.importToken,
        )
        store.write(profileId, document)
        LegacyPreferenceImportResult(
            imported = true,
            snapshot = decode(document),
            notes = mapped.notes,
        )
    }

    private fun decode(document: PlaybackPreferenceDocument): PlaybackPreferenceSnapshot =
        snapshot(PlaybackPreferenceSchema.decode(document))

    private fun snapshot(decoded: DecodedPlaybackPreferences): PlaybackPreferenceSnapshot =
        PlaybackPreferenceSnapshot(
            preferences = decoded.preferences,
            revision = decoded.document.revision,
            warnings = decoded.warnings,
            preservedUnknownValues = decoded.document.preservedUnknownValues,
            legacyImportToken = decoded.document.legacyImportToken,
        )

    private fun checkWritable(document: PlaybackPreferenceDocument) {
        require(document.schemaVersion <= CLEAN_PLAYBACK_PREFERENCE_SCHEMA_VERSION) {
            "A future playback-preference schema is read-only in this app version"
        }
    }
}
