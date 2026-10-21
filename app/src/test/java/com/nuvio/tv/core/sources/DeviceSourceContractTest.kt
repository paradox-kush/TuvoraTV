package com.nuvio.tv.core.sources

import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

class DeviceSourceContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun resetTombstoneAndEmptyDeltaAreDistinct() {
        val reset = json.decodeFromString<DevicePreferencePage>(
            """{"snapshot":false,"generation":2,"revision":7,"cursor":7,"has_more":false,"values":[{"profile_id":2,"source_id":"source","enabled":null,"revision":7}]}""")
        assertEquals(null, reset.values.single().enabled)
        assertEquals(7L, reset.values.single().revision)
        val empty = json.decodeFromString<DevicePreferencePage>(
            """{"snapshot":false,"generation":2,"revision":7,"cursor":7,"has_more":false,"values":[]}""")
        assertEquals(0, empty.values.size)
    }

    @Test fun addonConfigurationIsNotPrintedInDiagnostics() {
        val source = json.decodeFromString<DeviceSourceDescriptor>(
            """{"source_id":"source","kind":"addon","source_ref":"https://example.test/private-key/manifest.json","name":"Addon","account_enabled":true,"storage_profile":1}""")
        assertFalse(source.toString().contains("private-key"))
        assertEquals(1, source.storageProfile)
    }

    @Test fun conflictCarriesSavedChoiceAndRevision() {
        val result = json.decodeFromString<DevicePreferenceWriteResult>(
            """{"ok":false,"error":"revision_conflict","enabled":false,"revision":11}""")
        assertFalse(result.ok)
        assertEquals(false, result.enabled)
        assertEquals(11L, result.revision)
    }
}
