package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings

data class DebridProvider(
    val id: String,
    val displayName: String,
    val shortName: String
)

data class DebridServiceCredential(
    val provider: DebridProvider,
    val apiKey: String
)

object DebridProviders {
    const val TORBOX_ID = "torbox"
    const val REAL_DEBRID_ID = "realdebrid"

    val Torbox = DebridProvider(
        id = TORBOX_ID,
        displayName = "Torbox",
        shortName = "TB"
    )

    val RealDebrid = DebridProvider(
        id = REAL_DEBRID_ID,
        displayName = "Real-Debrid",
        shortName = "RD"
    )

    private val registered = listOf(Torbox, RealDebrid)

    fun all(): List<DebridProvider> = registered

    fun byId(id: String?): DebridProvider? {
        val normalized = id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return registered.firstOrNull { it.id.equals(normalized, ignoreCase = true) }
    }

    fun isSupported(id: String?): Boolean = byId(id) != null

    fun instantName(id: String?): String = "${displayName(id)} Instant"

    fun displayName(id: String?): String {
        return byId(id)?.displayName ?: id.toFallbackDisplayName()
    }

    fun shortName(id: String?): String {
        return byId(id)?.shortName ?: id?.trim()?.takeIf { it.isNotBlank() }?.uppercase().orEmpty()
    }

    fun configuredServices(settings: DebridSettings): List<DebridServiceCredential> {
        return buildList {
            settings.torboxApiKey.trim().takeIf { it.isNotBlank() }?.let { apiKey ->
                add(DebridServiceCredential(Torbox, apiKey))
            }
            settings.realDebridApiKey.trim().takeIf { it.isNotBlank() }?.let { apiKey ->
                add(DebridServiceCredential(RealDebrid, apiKey))
            }
        }
    }

    fun configuredSourceNames(settings: DebridSettings): List<String> {
        return configuredServices(settings).map { instantName(it.provider.id) }
    }

    private fun String?.toFallbackDisplayName(): String {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return "Debrid"
        return value
            .replace('-', ' ')
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase().replaceFirstChar { it.titlecase() }
            }
            .ifBlank { "Debrid" }
    }
}
