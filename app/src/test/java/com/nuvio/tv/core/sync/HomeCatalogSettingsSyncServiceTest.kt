package com.nuvio.tv.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogSettingsSyncServiceTest {
    @Test
    fun sharedSettingsMergePreservesUnknownRemoteFields() {
        val remote = buildJsonObject {
            put("future_setting", "preserved")
            put("hide_unreleased_content", false)
        }
        val local = buildJsonObject {
            put("hide_unreleased_content", true)
        }

        val merged = mergeHomeCatalogSettingsJson(remote, local)

        assertEquals("preserved", merged.getValue("future_setting").jsonPrimitive.content)
        assertEquals(true, merged.getValue("hide_unreleased_content").jsonPrimitive.content.toBoolean())
    }

    // Regression (device proof 2026-11-15, T3): a TV whose add-on never loaded exports no rows for it,
    // and the push replaced the whole stored `items` list, erasing the provider's layout (4 -> 0).

    private fun payloadJson(items: List<SyncCatalogItem>, hide: Boolean = false): JsonObject =
        Json { encodeDefaults = true }.encodeToJsonElement(
            SyncHomeCatalogPayload.serializer(),
            SyncHomeCatalogPayload(hideUnreleasedContent = hide, items = items)
        ).jsonObject

    private fun item(addon: String, catalog: String, order: Int, enabled: Boolean = true, title: String = "") =
        SyncCatalogItem(addonId = addon, type = "movie", catalogId = catalog, enabled = enabled, order = order, customTitle = title)

    private fun JsonObject.itemKeys(): List<String> =
        getValue("items").jsonArray.map {
            val o = it.jsonObject
            o.getValue("addon_id").jsonPrimitive.content + "/" + o.getValue("catalog_id").jsonPrimitive.content
        }

    @Test
    fun pushKeepsStoredRowsOfAddonsThatDidNotLoad() {
        val remote = payloadJson(listOf(item("x", "delta", 0, title = "Server Delta"), item("a", "bravo", 1), item("a", "charlie", 2)))
        val local = payloadJson(emptyList())

        val merged = mergeHomeCatalogSettingsJson(remote, local)

        assertEquals(listOf("x/delta", "a/bravo", "a/charlie"), merged.itemKeys())
    }

    @Test
    fun pushKeepsUnloadedRowInItsStoredPositionAndRenumbersFromZero() {
        val remote = payloadJson(listOf(item("x", "delta", 0, title = "Server Delta"), item("a", "bravo", 1), item("a", "charlie", 2)))
        val local = payloadJson(listOf(item("a", "charlie", 0), item("a", "bravo", 1)))

        val merged = mergeHomeCatalogSettingsJson(remote, local)

        assertEquals(listOf("x/delta", "a/charlie", "a/bravo"), merged.itemKeys())
        assertEquals(listOf(0, 1, 2), merged.getValue("items").jsonArray.map { it.jsonObject.getValue("order").jsonPrimitive.content.toInt() })
        assertEquals("Server Delta", merged.getValue("items").jsonArray.first().jsonObject.getValue("custom_title").jsonPrimitive.content)
    }

    @Test
    fun pushLocalValuesWinForRowsTheDeviceKnows() {
        val remote = payloadJson(listOf(item("a", "bravo", 0, enabled = true, title = "Old")))
        val local = payloadJson(listOf(item("a", "bravo", 0, enabled = false, title = "New")))

        val merged = mergeHomeCatalogSettingsJson(remote, local)

        val only = merged.getValue("items").jsonArray.single().jsonObject
        assertEquals("false", only.getValue("enabled").jsonPrimitive.content)
        assertEquals("New", only.getValue("custom_title").jsonPrimitive.content)
    }

    @Test
    fun pushDropsRemoteCollectionsTheDeviceNoLongerHas() {
        val collection = SyncCatalogItem(addonId = "", type = "", catalogId = "", order = 0, isCollection = true, collectionId = "c1")
        val remote = payloadJson(listOf(collection, item("a", "bravo", 1)))
        val local = payloadJson(listOf(item("a", "bravo", 0)))

        val merged = mergeHomeCatalogSettingsJson(remote, local)

        assertEquals(listOf("a/bravo"), merged.itemKeys())
    }

    @Test
    fun pushIgnoresMalformedRemoteRowsInsteadOfFailing() {
        val remote = buildJsonObject {
            put("items", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("addon_id", "x") })
                add(Json.encodeToJsonElement(SyncCatalogItem.serializer(), item("x", "delta", 0)))
            })
        }
        val local = payloadJson(listOf(item("a", "bravo", 0)))

        val merged = mergeHomeCatalogSettingsJson(remote, local)

        assertTrue("x/delta" in merged.itemKeys())
        assertEquals(2, merged.itemKeys().size)
    }

    @Test
    fun pushWithNoRemoteRowIsUnchanged() {
        val local = payloadJson(listOf(item("a", "bravo", 0)))

        assertEquals(local, mergeHomeCatalogSettingsJson(null, local))
    }
}
