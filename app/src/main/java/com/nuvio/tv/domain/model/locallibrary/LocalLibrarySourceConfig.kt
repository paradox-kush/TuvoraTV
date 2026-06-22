package com.nuvio.tv.domain.model.locallibrary

import androidx.compose.runtime.Immutable

/**
 * A user-configured source backing the synthetic Local Library addon.
 *
 * Secrets (passwords, API tokens) are NOT stored here — they live in the
 * encrypted credential store keyed by [id].
 */
@Immutable
data class LocalLibrarySourceConfig(
    val id: String,
    val displayName: String,
    val kind: SourceKind,
    /**
     * For [SourceKind.JELLYFIN] this is the server base URL (e.g. https://jellyfin.example.com).
     * For [SourceKind.SMB] this is the share URI (smb://host/share or //host/share[/sub/path]).
     * For [SourceKind.LOCAL_FILE] this is the persisted SAF tree URI.
     */
    val urlOrPath: String,
    /**
     * Optional non-secret auxiliary parameters (Jellyfin user id, SMB domain, etc.).
     * Kept generic so we don't need a new field per backend.
     */
    val params: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val lastScanAt: Long? = null,
    val itemCount: Int = 0,
    /** How many of [itemCount] items matched a TMDB id on the last scan. */
    val matchedCount: Int = 0
)
