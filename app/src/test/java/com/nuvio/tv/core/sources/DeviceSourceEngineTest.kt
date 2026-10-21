package com.nuvio.tv.core.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*


@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceSourceEngineTest {
    private inline fun assertFails(block: () -> Unit) { var failed = false; try { block() } catch (_: Throwable) { failed = true }; assertTrue(failed) }
    private inline fun <reified T: Throwable> assertFailsWith(block: () -> Unit) {
        var thrown: Throwable? = null
        try { block() } catch (e: Throwable) { thrown = e }
        assertTrue(thrown is T)
    }

    private val source = DeviceSourceDescriptor("source", "iptv", "frozen-playlist-key", "My IPTV", true, 1)
    private val json = Json { encodeDefaults = true }
    private class Memory(var value: String? = null) : DevicePreferenceStorage {
        var fail = false
        override fun read() = value
        override fun write(value: String) { if (fail) error("disk full"); this.value = value }
    }
    private fun cached(values: List<DevicePreferenceValue> = emptyList()) = Memory(json.encodeToString(DevicePreferenceDisk(
        binding = SavedDeviceBinding("setup", "Phone", "secret"), cursor = 0,
        sources = mapOf(1 to listOf(source)), values = values)))
    private fun engine(memory: Memory, local: Boolean = false, rpc: suspend (String, JsonObject) -> JsonElement = { _, _ -> error("offline") }): DeviceSourceEngine {
        var ids = 0
        return DeviceSourceEngine(memory, rpc, { "id-${++ids}" }, { "new-secret" }, { 1_000L }, "android", local)
    }
    private fun page() = json.encodeToJsonElement(DevicePreferencePage(false,1,0,0,false,emptyList()))

    @Test fun iptvOffIsDurableAndIsolatedFromAnotherDeviceAndProfile() {
        val first = cached(); val phone = engine(first); val tv = engine(cached())
        phone.setChoice(1, source.sourceId, false)
        assertFalse(phone.allows(1,"iptv",source.sourceRef))
        assertTrue(tv.allows(1,"iptv",source.sourceRef))
        assertFalse(engine(first).allows(1,"iptv",source.sourceRef))
        assertFalse("Unloaded profile cannot start discovery", phone.allows(2,"iptv",source.sourceRef))
        assertTrue(phone.state.value.disk.sources[1]!!.single().accountEnabled)
    }

    @Test fun failedDiskWriteCannotAcknowledgeASourceChoice() {
        val disk = cached(); val device = engine(disk); disk.fail = true
        assertFails { device.setChoice(1,source.sourceId,false) }
        assertTrue(device.allows(1,"iptv",source.sourceRef))
        assertTrue(device.state.value.disk.pending.isEmpty())
    }

    @Test fun lostAcknowledgementRetriesTheSameImmutableMutation() = runTest {
        val disk = cached(); val writes = mutableListOf<JsonObject>(); var failed = false
        val device = engine(disk) { name, args ->
            when(name) {
                "device_preferences_read" -> page()
                "device_preference_write" -> {
                    writes += args
                    if (!failed) { failed = true; error("connection closed after commit") }
                    json.encodeToJsonElement(DevicePreferenceWriteResult(true,false,1))
                }
                else -> error("unexpected RPC")
            }
        }
        device.setChoice(1,source.sourceId,false)
        device.refresh(1,false,false)
        assertEquals(1,device.state.value.disk.pending.size)
        device.refresh(1,false,false)
        assertEquals(2,writes.size)
        assertEquals(writes[0],writes[1])
        assertTrue(device.state.value.disk.pending.isEmpty())
        assertFalse(device.allows(1,"iptv",source.sourceRef))
    }

    @Test fun sourceDisabledDuringDiscoveryCancelsInFlightWork() = runTest {
        val device = engine(cached())
        val request = async { device.guarded(1,"iptv",source.sourceRef) { awaitCancellation() } }
        runCurrent()
        device.setChoice(1,source.sourceId,false)
        runCurrent()
        assertTrue(request.isCancelled)
        assertFailsWith<CancellationException> { request.await() }
    }

    @Test fun staleEnableConflictCannotUndoSavedOff() = runTest {
        val device = engine(cached()) { name, _ -> when(name) {
            "device_preferences_read" -> page()
            "device_preference_write" -> json.encodeToJsonElement(DevicePreferenceWriteResult(false,false,8,"revision_conflict"))
            else -> error("unexpected RPC")
        } }
        device.setChoice(1,source.sourceId,true)
        device.refresh(1,false,false)
        assertFalse(device.allows(1,"iptv",source.sourceRef))
        assertTrue(device.state.value.disk.pending.single().conflict)
    }

    @Test fun addonBaseUrlAndManifestShareTheSameDeviceChoice() {
        val disk = cached()
        disk.value = json.encodeToString(DevicePreferenceDisk(binding = SavedDeviceBinding("setup","TV","secret"), cursor = 0,
            sources = mapOf(1 to listOf(source.copy(kind = "addon", sourceRef = "https://addon.example/config/manifest.json")))))
        val device = engine(disk)
        device.setChoice(1,source.sourceId,false)
        assertFalse(device.allowsAddonRequest(1,"https://addon.example/config"))
        assertFalse(device.allowsAddonRequest(1,"https://addon.example/config/catalog/movie/top.json"))
        assertTrue(device.allowsAddonRequest(1,"https://addon.example/config-other/manifest.json"))
        assertEquals("https://addon.example/config?token=A",DeviceSourcePolicy.addonReference("https://addon.example/config/manifest.json?token=A"))
    }

    @Test fun reconnectWithNoLocalWorkMakesNoRequest() = runTest {
        var requests = 0
        val device = engine(cached()) { _, _ -> requests++; error("Must not request") }
        device.refresh(1,false,false)
        assertEquals(0,requests)
        assertFalse(DevicePreferenceRefreshPolicy.hasLocalWork(false,false,false))
        assertTrue(DevicePreferenceRefreshPolicy.hasLocalWork(false,true,false))
        assertTrue(DevicePreferenceRefreshPolicy.hasLocalWork(false,false,true))
    }

    @Test fun profileSwapSnapshotRefreshesSourceIdentityMappings() = runTest {
        val moved = source.copy(sourceId = "moved-source",sourceRef = "moved-playlist")
        val device = engine(cached()) { name, _ -> when(name) {
            "device_preferences_read" -> json.encodeToJsonElement(DevicePreferencePage(true,1,2,2,false,
                listOf(DevicePreferenceValue(1,moved.sourceId,false,2))))
            "device_sources_list" -> json.encodeToJsonElement(listOf(moved))
            else -> error("unexpected RPC")
        } }
        device.refresh(1,false,true)
        assertEquals(moved,device.state.value.disk.sources[1]!!.single())
        assertFalse(device.allows(1,"iptv",moved.sourceRef))
    }

    @Test fun corruptStorageFailsClosedRatherThanEnablingSources() {
        val device = engine(Memory("broken"))
        assertEquals("storage_error",device.state.value.mode)
        assertFalse(device.allows(1,"iptv",source.sourceRef))
    }

    @Test fun guestChoicesSurviveEmptyStartupListsAndFreshUiIds() {
        val addon = source.copy(sourceId = "addon-id",kind = "addon",sourceRef = "https://guest.example/manifest.json")
        val disk = Memory(json.encodeToString(DevicePreferenceDisk(sources = mapOf(1 to listOf(source,addon)))))
        val before = engine(disk,local = true)
        before.setChoice(1,source.sourceId,false)
        before.setChoice(1,addon.sourceId,false)
        before.localSources(1,emptyList()) // DataStore's initial value before the real source list arrives.
        assertFalse(before.allows(1,"iptv",source.sourceRef))
        assertFalse(before.allowsAddonRequest(1,"https://guest.example/catalog/movie/top.json"))
        val restarted = engine(disk,local = true)
        restarted.localSources(1,listOf(source.copy(sourceId = "new-ui-id"),addon.copy(sourceId = "new-addon-ui-id")))
        assertEquals(listOf(source.sourceId,addon.sourceId),restarted.state.value.disk.sources[1]!!.map { it.sourceId })
        assertFalse(restarted.allows(1,"iptv",source.sourceRef))
        assertFalse(restarted.allowsAddonRequest(1,addon.sourceRef))
        restarted.setChoice(1,source.sourceId,true)
        assertTrue(restarted.allows(1,"iptv",source.sourceRef))
        assertFalse(restarted.allowsAddonRequest(1,addon.sourceRef))
    }

    @Test fun configuredAddonsOnTheSameHostKeepIndependentChoices() {
        val root = source.copy(sourceId = "root",kind = "addon",sourceRef = "https://addon.example")
        val path = root.copy(sourceId = "path",sourceRef = "https://addon.example/config")
        val query = root.copy(sourceId = "query",sourceRef = "https://addon.example?token=B")
        val disk = Memory(json.encodeToString(DevicePreferenceDisk(binding = SavedDeviceBinding("setup","Phone","secret"),
            cursor = 0,sources = mapOf(1 to listOf(root,path,query)))))
        val device = engine(disk)
        device.setChoice(1,root.sourceId,false)
        assertFalse(device.allowsAddonRequest(1,root.sourceRef + "/manifest.json"))
        assertTrue(device.allowsAddonRequest(1,path.sourceRef + "/manifest.json"))
        assertTrue(device.allowsAddonRequest(1,path.sourceRef + "/catalog/movie/top.json"))
        assertTrue(device.allowsAddonRequest(1,"https://addon.example/catalog/movie/top.json?token=B"))
        device.setChoice(1,query.sourceId,false)
        assertFalse(device.allowsAddonRequest(1,"https://addon.example/stream/movie/id.json?token=B"))
        assertTrue(device.allowsAddonRequest(1,path.sourceRef + "/catalog/movie/top.json"))
    }
}
