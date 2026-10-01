package com.nuvio.tv.core.sync

import com.nuvio.tv.data.remote.supabase.SupabaseProviderCredential
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal object ProviderCredentialIds {
    const val MDBLIST = "mdblist"
    const val ANIMESKIP = "animeskip"

    fun debrid(providerId: String): String = "debrid:$providerId"
}

internal data class ProviderCredentialValue(
    val provider: String,
    val field: String,
    val value: String
) {
    fun credentialJson() = buildJsonObject {
        put(field, value.trim())
    }
}

internal data class ProviderCredentialSnapshot(
    val profileId: Int,
    val values: List<ProviderCredentialValue>
) {
    init {
        require(values.map(ProviderCredentialValue::provider).distinct().size == values.size)
    }

    /**
     * The values to push after a local edit: only providers whose value differs from the last-synced [baseline].
     * B82 — pushing the whole snapshot upserted this device's stale blank placeholders over keys set elsewhere.
     */
    fun changedSince(baseline: ProviderCredentialSnapshot?): ProviderCredentialSnapshot {
        if (baseline == null) return this
        val baselineByProvider = baseline.values.associateBy { it.provider.lowercase() }
        return copy(values = values.filter { baselineByProvider[it.provider.lowercase()] != it })
    }

    fun mergeRemote(rows: List<SupabaseProviderCredential>): ProviderCredentialSnapshot {
        val remoteByProvider = rows.associateBy { it.provider.lowercase() }
        return copy(
            values = values.map { local ->
                val remote = remoteByProvider[local.provider] ?: return@map local
                val element = remote.credentialJson[local.field] as? JsonPrimitive
                    ?: error("Invalid credential payload for ${local.provider}")
                val value = element.contentOrNull
                    ?: error("Invalid credential value for ${local.provider}")
                local.copy(value = value.trim())
            }
        )
    }
}

internal fun shouldSeedProviderCredentials(
    snapshot: ProviderCredentialSnapshot,
    rows: List<SupabaseProviderCredential>
): Boolean {
    val remoteProviders = rows.mapTo(mutableSetOf()) { row -> row.provider.lowercase() }
    return snapshot.values.any { credential -> credential.provider.lowercase() !in remoteProviders }
}
