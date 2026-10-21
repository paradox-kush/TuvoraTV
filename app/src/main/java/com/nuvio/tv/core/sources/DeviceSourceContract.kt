package com.nuvio.tv.core.sources

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Transport contract for 20261021120000_device_source_preferences.sql. Not account enabled state. */
@Serializable
data class DeviceSourceDescriptor(
    @SerialName("source_id") val sourceId: String,
    val kind: String,
    @SerialName("source_ref") val sourceRef: String,
    val name: String,
    @SerialName("account_enabled") val accountEnabled: Boolean,
    @SerialName("storage_profile") val storageProfile: Int,
) {
    // sourceRef can contain an addon configuration secret. Never expose it in diagnostics.
    override fun toString(): String = "DeviceSourceDescriptor(id=$sourceId,kind=$kind)"
}

@Serializable
data class DeviceSetupSummary(
    val id: String,
    val label: String,
    val platform: String,
    val generation: Long,
    val revision: Long,
    @SerialName("last_used_at") val lastUsedAt: String,
)

@Serializable
data class DeviceSetupResult(val id: String, val generation: Long)

@Serializable
data class DevicePreferenceValue(
    @SerialName("profile_id") val profileId: Int,
    @SerialName("source_id") val sourceId: String,
    val enabled: Boolean? = null,
    val revision: Long,
)

@Serializable
data class DevicePreferencePage(
    val snapshot: Boolean,
    val generation: Long,
    val revision: Long,
    val cursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    val values: List<DevicePreferenceValue>,
)

@Serializable
data class DevicePreferenceWriteResult(
    val ok: Boolean,
    val enabled: Boolean? = null,
    val revision: Long,
    val error: String? = null,
)
