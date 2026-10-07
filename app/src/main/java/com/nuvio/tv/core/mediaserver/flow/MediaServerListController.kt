package com.nuvio.tv.core.mediaserver.flow

import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.client.HealthStatus
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ServerRowModel(val entry: MediaServerEntry, val status: ServerStatus, val checking: Boolean)

/**
 * The server list's health badges. A reachability + token check is made ONCE per visit (the screen calls
 * [checkOnce] when it becomes visible) - there is no timer and no retry loop, so an offline server costs one failed
 * request per visit, not one per minute (CLAUDE.md recurring-network rule). Rows render immediately from the
 * token-based state and update as the checks answer.
 */
internal class MediaServerListController(
    private val services: MediaServerServices,
    private val scope: CoroutineScope,
) {
    private val health = MutableStateFlow<Map<String, HealthStatus>>(emptyMap())
    private val checking = MutableStateFlow<Set<String>>(emptySet())
    private var job: Job? = null

    /** The rows for [entries] given the latest checks. Pure over the flows' current values (the screen re-reads on any change). */
    fun rows(entries: List<MediaServerEntry>, expired: Set<String>, healthMap: Map<String, HealthStatus>, checkingKeys: Set<String>): List<ServerRowModel> =
        entries.map { entry ->
            val signedIn = services.isSignedIn(entry)
            ServerRowModel(
                entry = entry,
                status = MediaServerStatusPolicy.of(entry, signedIn, entry.serverKey in expired, healthMap[entry.serverKey]),
                checking = entry.serverKey in checkingKeys,
            )
        }

    val healthState: StateFlow<Map<String, HealthStatus>> = health.asStateFlow()
    val checkingState: StateFlow<Set<String>> = checking.asStateFlow()

    /** Asks every signed-in, enabled server once. A check already running is not duplicated. */
    fun checkOnce(entries: List<MediaServerEntry>) {
        if (job?.isActive == true) return
        val targets = entries.filter { MediaServerStatusPolicy.canCheckHealth(it, services.isSignedIn(it)) }
        if (targets.isEmpty()) return
        checking.value = targets.map { it.serverKey }.toSet()
        job = scope.launch {
            try {
                coroutineScope {
                    targets.map { entry ->
                        async {
                            val result = services.health(entry)
                            health.update { it + (entry.serverKey to result) }
                            checking.update { it - entry.serverKey }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) {
                checking.value = emptySet()
                throw e
            }
        }
    }

    /** A token was saved / removed: the old verdict no longer applies. */
    fun forget(serverKey: String) = health.update { it - serverKey }
}
