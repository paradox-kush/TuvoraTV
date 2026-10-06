package com.nuvio.tv.core.mediaserver.store

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The certificates this device pinned by trust-on-first-use (design 5.5), per `host:port`. Fingerprints are
 * public values (not secrets) and are never synced: a pin is a fact about THIS device's decision. Only ever
 * written after the user explicitly accepted a certificate the system did not trust.
 */
internal class MediaServerTrustStore(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val erase: () -> Unit,
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private var cache: Map<String, String>? = null

    fun pinned(authority: String): String? = synchronized(lock) { pins()[authority.lowercase()] }

    fun pin(authority: String, fingerprint: String) = synchronized(lock) {
        val next = pins() + (authority.lowercase() to fingerprint)
        save(json.encodeToString(serializer, next))
        cache = next
    }

    fun unpin(authority: String) = synchronized(lock) {
        val next = pins() - authority.lowercase()
        if (next.isEmpty()) erase() else save(json.encodeToString(serializer, next))
        cache = next
    }

    fun clearAll() = synchronized(lock) {
        erase()
        cache = emptyMap()
    }

    private fun pins(): Map<String, String> =
        cache ?: (load()?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()).also { cache = it }
}
