package com.nuvio.tv.core.sync

import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.catalogTypeKey
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.enabledAddons
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class LocalHomeCatalogSettingsState(
    val orderKeys: List<String> = emptyList(),
    val disabledKeys: Set<String> = emptySet(),
    val customTitles: Map<String, String> = emptyMap(),
    val hideUnreleasedContent: Boolean = false
)

private data class HomeCatalogSyncEntry(
    val key: String,
    val addonId: String = "",
    val addonBaseUrl: String = "",
    val type: String = "",
    val catalogId: String = "",
    val catalogName: String = "",
    val isCollection: Boolean = false,
    val collectionId: String = ""
)

internal fun homeCatalogKey(addonId: String, type: String, catalogId: String): String {
    return catalogRowLegacyKey(addonId, type, catalogId)
}

internal fun homeCollectionKey(collectionId: String): String {
    return "collection_${collectionId}"
}

internal fun List<String>.withLocalServerKeys(local: List<String>): List<String> =
    local.withIndex()
        .filter { ServerCatalog.isServerKey(it.value) }
        .fold(filterNot(ServerCatalog::isServerKey).toMutableList()) { keys, (index, key) ->
            keys.apply { add(index.coerceAtMost(size), key) }
        }

internal fun homeLegacyDisabledCatalogKey(
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): String {
    return "${addonBaseUrl}_${catalogTypeKey(type)}_${catalogId}_${catalogName}"
}

internal fun hasLegacyHomeCatalogDisabledKeyFormat(key: String): Boolean {
    return key.contains("://")
}

internal fun isHomeCatalogDisabled(
    disabledKeys: Set<String>,
    addonId: String,
    addonBaseUrl: String,
    type: String,
    catalogId: String,
    catalogName: String
): Boolean {
    return homeCatalogKey(addonId, type, catalogId) in disabledKeys ||
        homeLegacyDisabledCatalogKey(addonBaseUrl, type, catalogId, catalogName) in disabledKeys
}

internal fun buildHomeCatalogSyncPayload(
    addons: List<Addon>,
    collections: List<Collection>,
    localState: LocalHomeCatalogSettingsState
): SyncHomeCatalogPayload {
    val catalogEntries = buildCatalogEntries(addons)
    val collectionEntries = collections.map { collection ->
        HomeCatalogSyncEntry(
            key = homeCollectionKey(collection.id),
            isCollection = true,
            collectionId = collection.id
        )
    }
    val entryByKey = (catalogEntries + collectionEntries).associateBy { it.key }
    val catalogKeys = catalogEntries.map { it.key }
    val collectionKeys = collectionEntries.map { it.key }

    val savedValid = localState.orderKeys
        .asSequence()
        .filter { it in entryByKey }
        .distinct()
        .toList()
    val savedSet = savedValid.toSet()
    val mergedOrder = savedValid +
        catalogKeys.filterNot { it in savedSet } +
        collectionKeys.filterNot { it in savedSet }

    val items = mergedOrder.mapIndexedNotNull { index, key ->
        val entry = entryByKey[key] ?: return@mapIndexedNotNull null
        if (entry.isCollection) {
            SyncCatalogItem(
                addonId = "",
                type = "",
                catalogId = "",
                enabled = key !in localState.disabledKeys,
                order = index,
                customTitle = localState.customTitles[key].orEmpty(),
                isCollection = true,
                collectionId = entry.collectionId
            )
        } else {
            SyncCatalogItem(
                addonId = entry.addonId,
                type = entry.type,
                catalogId = entry.catalogId,
                enabled = !isHomeCatalogDisabled(
                    disabledKeys = localState.disabledKeys,
                    addonId = entry.addonId,
                    addonBaseUrl = entry.addonBaseUrl,
                    type = entry.type,
                    catalogId = entry.catalogId,
                    catalogName = entry.catalogName
                ),
                order = index,
                customTitle = localState.customTitles[key].orEmpty(),
                isCollection = false,
                collectionId = ""
            )
        }
    }

    return SyncHomeCatalogPayload(
        hideUnreleasedContent = localState.hideUnreleasedContent,
        items = items
    )
}

private fun buildCatalogEntries(addons: List<Addon>): List<HomeCatalogSyncEntry> {
    val entries = mutableListOf<HomeCatalogSyncEntry>()
    val seenKeys = mutableSetOf<String>()

    addons.enabledAddons().forEach { addon ->
        addon.catalogs
            .filter { it.shouldShowOnHomeForSync() }
            .forEach { catalog ->
                val key = homeCatalogKey(addon.id, catalog.apiType, catalog.id)
                if (seenKeys.add(key)) {
                    entries.add(
                        HomeCatalogSyncEntry(
                            key = key,
                            addonId = addon.id,
                            addonBaseUrl = addon.baseUrl,
                            type = catalog.apiType,
                            catalogId = catalog.id,
                            catalogName = catalog.name
                        )
                    )
                }
            }
    }

    return entries
}

private fun CatalogDescriptor.shouldShowOnHomeForSync(): Boolean {
    if (isSearchOnlyCatalog()) {
        return false
    }
    return !hasExplicitShowInHome || showInHome
}

private fun JsonElement.stringField(name: String): String? =
    (this as? JsonObject)?.get(name)?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.contentOrNull

private fun JsonElement.isCollectionItem(): Boolean =
    ((this as? JsonObject)?.get("is_collection") as? JsonPrimitive)?.booleanOrNull == true

/** Identity of a stored catalog row, or null for a collection or a row the decoder would reject. */
private fun JsonElement.catalogRowIdentity(): String? {
    if (isCollectionItem()) return null
    val addonId = stringField("addon_id") ?: return null
    val type = stringField("type") ?: return null
    val catalogId = stringField("catalog_id") ?: return null
    return homeCatalogKey(addonId, type, catalogId)
}

private fun JsonElement.storedOrder(): Int =
    ((this as? JsonObject)?.get("order") as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE

/**
 * A TV exports rows only for add-ons that are installed AND whose manifest loaded. A push built from that
 * export replaced the whole stored list, so a TV that had not loaded an add-on erased the rows the account
 * already holds for it (device proof 2026-11-15, T3: 4 rows -> 0). The phone and desktop push from stored
 * rows and never do this.
 *
 * Keeps every stored catalog row the device has no row for, in its stored position, and renumbers from 0.
 * The device's own rows win for rows it knows; stored collections are not carried (the device is the source
 * of truth for collections, as before); rows the decoder would reject are skipped; carried rows keep any
 * fields this build does not model (e.g. the phone's `key`).
 */
internal fun carryForwardStoredHomeCatalogItems(
    storedItems: JsonElement?,
    localItems: JsonElement?
): JsonArray {
    val local = (localItems as? JsonArray).orEmpty().sortedBy { it.storedOrder() }
    val stored = (storedItems as? JsonArray).orEmpty().sortedBy { it.storedOrder() }
    val localKeys = local.mapNotNull { it.catalogRowIdentity() }.toSet()
    val merged = local.toMutableList()
    val carried = mutableSetOf<String>()
    stored.forEachIndexed { index, row ->
        val key = row.catalogRowIdentity() ?: return@forEachIndexed
        if (key in localKeys || !carried.add(key)) return@forEachIndexed
        merged.add(index.coerceAtMost(merged.size), row)
    }
    return JsonArray(
        merged.mapIndexed { index, row ->
            val obj = row as? JsonObject ?: return@mapIndexed row
            JsonObject(obj + ("order" to JsonPrimitive(index)))
        }
    )
}
