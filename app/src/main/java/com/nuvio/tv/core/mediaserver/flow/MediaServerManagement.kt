package com.nuvio.tv.core.mediaserver.flow

import com.nuvio.tv.core.mediaserver.MsLog as Logger
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerHomeRow
import com.nuvio.tv.core.mediaserver.MediaServerAccounts
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the server screens do to an entry, in one place: every change tells [onChanged] (Home rows, the Home layout
 * list and search re-read), and the two that end a session - sign out and remove - first ask the server to forget this
 * device's token (`POST /Sessions/Logout`, best effort with a short timeout: an unreachable server must never block
 * the user's own removal; owner decision 2026-10-06).
 */
internal class MediaServerManagement(
    private val accounts: MediaServerAccounts,
    private val services: MediaServerServices,
    private val onChanged: (MediaServerEntry) -> Unit = {},
    private val logoutTimeoutMs: Long = LOGOUT_TIMEOUT_MS,
) {
    private val log = Logger.withTag("MediaServerManagement")

    fun rename(entry: MediaServerEntry, name: String): Boolean = accounts.rename(entry, name).also { if (it) onChanged(entry) }

    fun setEnabled(entry: MediaServerEntry, enabled: Boolean): Boolean = accounts.setEnabled(entry, enabled).also { if (it) onChanged(entry) }

    fun setSyncAddress(entry: MediaServerEntry, sync: Boolean): Boolean = accounts.setSyncAddress(entry, sync).also { if (it) onChanged(entry) }

    fun setHomeRow(entry: MediaServerEntry, row: MediaServerHomeRow, on: Boolean): Boolean {
        val rows = if (on) entry.homeRows + row else entry.homeRows - row
        return accounts.setHomeRows(entry, rows).also { if (it) onChanged(entry) }
    }

    fun setHomeLibrary(entry: MediaServerEntry, viewId: String, name: String, on: Boolean): Boolean =
        accounts.setHomeLibrary(entry, viewId, name, on).also { if (it) onChanged(entry) }

    /** Ends this device's session (server-side first, while the token still exists), keeping the entry as "sign in". */
    suspend fun signOut(entry: MediaServerEntry) {
        logoutBestEffort(entry)
        accounts.signOut(entry)
        onChanged(entry)
    }

    /** Removes the entry; [purgeSavedData] also clears its Library / Continue Watching ids. */
    suspend fun remove(entry: MediaServerEntry, purgeSavedData: Boolean): Boolean {
        logoutBestEffort(entry)
        val removed = accounts.remove(entry, purgeSavedData)
        if (removed) onChanged(entry)
        return removed
    }

    private suspend fun logoutBestEffort(entry: MediaServerEntry) {
        val client = services.clientFor(entry) ?: return
        try {
            withTimeoutOrNull(logoutTimeoutMs) { client.logout() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "server-side logout failed: ${e::class.simpleName}" }
        }
    }

    private companion object {
        const val LOGOUT_TIMEOUT_MS = 5_000L
    }
}
