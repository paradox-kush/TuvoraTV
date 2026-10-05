package com.nuvio.tv.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

/** B03 (D4) — a device's stale whole-blob push must not revert a website edit to another key. */
class SettingsBlobMergeTest {

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    @Test
    fun aWebEditToAnotherKeySurvivesALocalChange() {
        val base = j("""{"theme":{"name":"dark","amoled":false},"player":{"speed":1.0}}""")
        val local = j("""{"theme":{"name":"dark","amoled":true},"player":{"speed":1.0}}""")   // device: amoled on
        val remote = j("""{"theme":{"name":"light","amoled":false},"player":{"speed":1.0}}""") // web: light theme
        assertEquals(j("""{"theme":{"name":"light","amoled":true},"player":{"speed":1.0}}"""), SettingsBlobMerge.merge(base, local, remote))
    }

    @Test
    fun theLocalEditWinsOnTheSameKey() {
        val base = j("""{"player":{"speed":1.0}}""")
        assertEquals(j("""{"player":{"speed":1.5}}"""), SettingsBlobMerge.merge(base, j("""{"player":{"speed":1.5}}"""), j("""{"player":{"speed":2.0}}""")))
    }

    @Test
    fun keysAddedOnEitherSideAreKept() {
        val base = j("""{"a":{"x":1}}""")
        val merged = SettingsBlobMerge.merge(base, j("""{"a":{"x":1,"local":true}}"""), j("""{"a":{"x":1,"web":true},"b":{"y":2}}"""))
        assertEquals(j("""{"a":{"x":1,"web":true,"local":true},"b":{"y":2}}"""), merged)
    }

    @Test
    fun noBaseKeepsTheOldWholeBlobBehaviour() {
        val local = j("""{"player":{"speed":1.5}}""")
        assertEquals(local, SettingsBlobMerge.merge(null, local, j("""{"player":{"speed":2.0}}""")))
        assertEquals(local, SettingsBlobMerge.merge(j("{}"), local, null))
    }

    @Test
    fun anUnchangedDeviceAdoptsTheServerBlob() {
        val base = j("""{"player":{"speed":1.0},"theme":{"name":"dark"}}""")
        val remote = j("""{"player":{"speed":2.0},"theme":{"name":"light"}}""")
        assertEquals(remote, SettingsBlobMerge.merge(base, base, remote))
    }
}
