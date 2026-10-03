package com.nuvio.tv.core.sync

import com.google.gson.Gson
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.mergeEditOntoServer
import com.nuvio.tv.core.iptv.pairingPayloadToXtreamAccount
import com.nuvio.tv.data.local.decodeXtreamAccountsJson
import com.nuvio.tv.data.remote.supabase.SupabaseIptvPlaylist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F46 (twin of Mobile's StalkerIdentitySyncTest): Device ID 2 / Signature / STB model / HW version
 * ride every place a Stalker playlist's identity already rides — the sync row both ways, the 3-way
 * edit merge, the pairing payload, the local Gson store, and the "same connection" verify gate.
 */
class StalkerIdentitySyncTest {

    private val stalker = XtreamAccount(
        id = "stalker|http://p:8080|00:1A:79:AA:BB:CC", name = "Portal", baseUrl = "http://p:8080",
        username = "", password = "", sourceType = XtreamAccount.SOURCE_STALKER,
        portalUrl = "http://p:8080", macAddress = "00:1A:79:AA:BB:CC",
    )
    private val full = stalker.copy(deviceId2 = "DEV2", signature = "SIG", stbModel = "MAG254", hwVersion = "2.6-IB-00")

    @Test
    fun `push carries the entered fields`() {
        val row = playlistPushJson(full, sortOrder = 0)
        assertEquals("device_id2", "DEV2", row["device_id2"]!!.jsonPrimitive.content)
        assertEquals("signature", "SIG", row["signature"]!!.jsonPrimitive.content)
        assertEquals("stb_model", "MAG254", row["stb_model"]!!.jsonPrimitive.content)
        assertEquals("hw_version", "2.6-IB-00", row["hw_version"]!!.jsonPrimitive.content)
    }

    /** The server keeps a column whose key is ABSENT, so a cleared field must travel as explicit null. */
    @Test
    fun `push sends cleared fields as explicit nulls`() {
        val row = playlistPushJson(stalker, sortOrder = 0)
        for (key in listOf("device_id2", "signature", "stb_model", "hw_version")) {
            assertTrue("$key present", key in row)
            assertEquals("$key null", JsonNull, row[key])
        }
    }

    @Test
    fun `pull maps the columns and a pushed row pulls back to the same account`() {
        val json = Json { ignoreUnknownKeys = true }
        val pulled = json.decodeFromJsonElement(SupabaseIptvPlaylist.serializer(), playlistPushJson(full, 0))
            .toXtreamAccountOrNull()!!
        assertEquals("device_id2", "DEV2", pulled.deviceId2)
        assertEquals("signature", "SIG", pulled.signature)
        assertEquals("stb_model", "MAG254", pulled.stbModel)
        assertEquals("hw_version", "2.6-IB-00", pulled.hwVersion)
        assertEquals("round trip", playlistPushJson(full, 0), playlistPushJson(pulled, 0))
    }

    @Test
    fun `an identity edit is a connection change`() {
        assertTrue("same", full.sameConnectionAs(full))
        assertFalse("device_id2", stalker.copy(deviceId2 = "X").sameConnectionAs(stalker))
        assertFalse("signature", stalker.copy(signature = "X").sameConnectionAs(stalker))
        assertFalse("stb_model", stalker.copy(stbModel = "X").sameConnectionAs(stalker))
        assertFalse("hw_version", stalker.copy(hwVersion = "X").sameConnectionAs(stalker))
    }

    @Test
    fun `a stale form keeps the identity another device set`() {
        val server = stalker.copy(stbModel = "MAG322", hwVersion = "HW-S")
        val merged = mergeEditOntoServer(server, stalker, stalker.copy(name = "Renamed", deviceId2 = "DEV2"))
        assertEquals("untouched field keeps the server value", "MAG322", merged.stbModel)
        assertEquals("hw", "HW-S", merged.hwVersion)
        assertEquals("the edited field wins", "DEV2", merged.deviceId2)
    }

    @Test
    fun `the pairing page's advanced fields land on the account`() {
        val payload = Json.parseToJsonElement(
            """{"source_type":"stalker","portal_url":"http://p:8080","mac_address":"00:1A:79:AA:BB:CC",
               "device_id2":" DEV2 ","signature":"SIG","stb_model":"MAG254","hw_version":"2.6-IB-00"}"""
        )
        val acc = pairingPayloadToXtreamAccount(payload)!!
        assertEquals("device_id2", "DEV2", acc.deviceId2)
        assertEquals("signature", "SIG", acc.signature)
        assertEquals("stb_model", "MAG254", acc.stbModel)
        assertEquals("hw_version", "2.6-IB-00", acc.hwVersion)
    }

    @Test
    fun `the local store keeps the fields and loads pre F46 json with them blank`() {
        val gson = Gson()
        val stored = decodeXtreamAccountsJson(gson, gson.toJson(listOf(full))).single()
        assertEquals("device_id2 kept", "DEV2", stored.deviceId2)
        assertEquals("signature kept", "SIG", stored.signature)
        assertEquals("stb_model kept", "MAG254", stored.stbModel)
        assertEquals("hw_version kept", "2.6-IB-00", stored.hwVersion)
        val old = """[{"id":"s","name":"n","baseUrl":"","username":"","password":"","sourceType":"stalker",
            "portalUrl":"http://p","macAddress":"00:1A:79:AA:BB:CC","deviceId":"DEV1","sendDeviceId":true}]"""
        val acc = decodeXtreamAccountsJson(gson, old).single()
        assertEquals("device id kept", "DEV1", acc.deviceId)
        assertEquals("device_id2", "", acc.deviceId2)
        assertEquals("signature", "", acc.signature)
        assertEquals("stb_model", "", acc.stbModel)
        assertEquals("hw_version", "", acc.hwVersion)
    }
}
