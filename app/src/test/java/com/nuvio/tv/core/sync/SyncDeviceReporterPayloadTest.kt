package com.nuvio.tv.core.sync

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncDeviceReporterPayloadTest {
    @Test
    fun `report_device payload carries p_app_version`() {
        val payload = SyncDeviceReporter.reportPayload("client-1", "Living Room", "1.19.2")
        assertEquals("version", "1.19.2", payload["p_app_version"]?.jsonPrimitive?.content)
        assertEquals("client", "client-1", payload["p_client_id"]?.jsonPrimitive?.content)
        assertEquals("name", "Living Room", payload["p_device_name"]?.jsonPrimitive?.content)
        assertEquals("platform", "tv", payload["p_platform"]?.jsonPrimitive?.content)
    }
}
