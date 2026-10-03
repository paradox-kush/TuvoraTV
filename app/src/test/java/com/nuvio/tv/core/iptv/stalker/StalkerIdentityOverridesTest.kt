package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.nuvio.tv.core.iptv.XtreamAccount
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * F46 — the optional Model / Device ID 2 / Signature / HW Version fields (twin of Mobile's
 * StalkerIdentityOverridesTest). BLANK must mean exactly what Tuvora sent before the fields existed:
 * portals pin the first identity they see (stock Ministra getProfile, Xtream-Codes lock_device
 * compares sn / device_id / device_id2 / hw_version), so a changed default locks working lines out.
 */
class StalkerIdentityOverridesTest {

    private val mac = "00:1A:79:58:B3:A6"

    // --- StalkerProtocol ----------------------------------------------------------------------

    @Test
    fun `blank device id 2 and signature keep the identity we always sent`() {
        val before = StalkerProtocol.deriveDeviceIdentity(mac, serialOverride = "SN1", deviceIdOverride = "DEV1")
        val blank = StalkerProtocol.deriveDeviceIdentity(
            mac, serialOverride = "SN1", deviceIdOverride = "DEV1", deviceId2Override = "  ", signatureOverride = "",
        )
        assertEquals("blank overrides change nothing", before, blank)
        assertEquals("device_id2 mirrors device_id when not entered", "DEV1", blank.deviceId2)
    }

    @Test
    fun `an entered device id 2 is sent verbatim and feeds the derived signature`() {
        val id = StalkerProtocol.deriveDeviceIdentity(mac, deviceIdOverride = "DEV1", deviceId2Override = " DEV2 ")
        assertEquals("device_id", "DEV1", id.deviceId)
        assertEquals("device_id2", "DEV2", id.deviceId2)
        assertEquals(
            "signature folds in the entered device_id2",
            StalkerProtocol.sha256Hex(mac + id.serialNumber + "DEV1" + "DEV2").uppercase(),
            id.signature,
        )
    }

    @Test
    fun `an entered signature is sent verbatim`() {
        assertEquals("signature", "ABCDEF", StalkerProtocol.deriveDeviceIdentity(mac, signatureOverride = " ABCDEF ").signature)
    }

    // --- StalkerMagPresets.pinned -------------------------------------------------------------

    @Test
    fun `no model and no hw version pins nothing`() {
        assertNull(StalkerMagPresets.pinned(null, null))
        assertNull(StalkerMagPresets.pinned("  ", ""))
        assertEquals("default first", StalkerMagPresets.DEFAULT, StalkerMagPresets.initial("", " "))
    }

    @Test
    fun `a known model takes that box's firmware with the typed model`() {
        val p = StalkerMagPresets.pinned("mag254", null)!!
        assertEquals("stb_type as typed", "mag254", p.stbType)
        assertEquals("image", StalkerMagPresets.MAG254_STRICT.imageVersion, p.imageVersion)
        assertEquals("hw", StalkerMagPresets.MAG254_STRICT.hwVersion, p.hwVersion)
        assertEquals("ua", StalkerMagPresets.MAG254_STRICT.userAgent, p.userAgent)
        assertEquals("x-ua", "Model: mag254; Link: WiFi", p.xUserAgent)
    }

    @Test
    fun `an unknown model rides the default box and a hw version alone keeps the default model`() {
        val p = StalkerMagPresets.pinned("MAG424", null)!!
        assertEquals("stb_type", "MAG424", p.stbType)
        assertEquals("image", StalkerMagPresets.DEFAULT.imageVersion, p.imageVersion)
        val hw = StalkerMagPresets.pinned(null, " 2.6-IB-00 ")!!
        assertEquals("hw", "2.6-IB-00", hw.hwVersion)
        assertEquals("model", StalkerMagPresets.DEFAULT.stbType, hw.stbType)
        assertEquals("x-ua", StalkerMagPresets.DEFAULT.xUserAgent, hw.xUserAgent)
    }

    @Test
    fun `a pinned identity is never walked down the ladder`() {
        assertNull(StalkerMagPresets.next(StalkerMagPresets.pinned("MAG254", "2.6-IB-00")))
    }

    // --- StalkerSession: what reaches the portal ----------------------------------------------

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var profileBody = """{"js":{"id":"1","status":0}}"""

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.isLoggable(any(), any()) } returns false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val body = when (request.url.queryParameter("action")) {
                    "handshake" -> """{"js":{"token":"T"}}"""
                    "get_profile" -> profileBody
                    "get_genres" -> """{"js":[{"id":"1","title":"Sports"}]}"""
                    else -> """{"js":[]}"""
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        unmockkStatic(Log::class)
    }

    private fun account(deviceId2: String = "", signature: String = "", stbModel: String = "", hwVersion: String = "") =
        XtreamAccount(
            id = "f46", name = "portal", baseUrl = "", username = "", password = "", sourceType = "stalker",
            portalUrl = server.url("/").toString().trimEnd('/'), macAddress = mac, deviceId = "DEV1",
            deviceId2 = deviceId2, signature = signature, stbModel = stbModel, hwVersion = hwVersion,
        )

    private fun profile() = requests.first { it.url.queryParameter("action") == "get_profile" }

    @Test
    fun `blank fields put the pre F46 identity on the wire`() = runBlocking {
        val session = StalkerSession(account(), OkHttpClient())
        session.request(mapOf("type" to "itv", "action" to "get_genres"))
        session.shutdown()
        val p = profile()
        val legacy = StalkerProtocol.deriveDeviceIdentity(mac, deviceIdOverride = "DEV1")
        assertEquals("device_id2", "DEV1", p.url.queryParameter("device_id2"))
        assertEquals("signature", legacy.signature, p.url.queryParameter("signature"))
        assertEquals("stb_type", "MAG250", p.url.queryParameter("stb_type"))
        assertEquals("hw_version", "1.7-BD-00", p.url.queryParameter("hw_version"))
        assertEquals("x-ua", StalkerMagPresets.DEFAULT.xUserAgent, p.headers["X-User-Agent"])
    }

    @Test
    fun `entered fields reach get_profile and every request's headers`() = runBlocking {
        val session = StalkerSession(
            account(deviceId2 = "DEV2", signature = "SIG", stbModel = "MAG322", hwVersion = "2.6-IB-00"),
            OkHttpClient(),
        )
        session.request(mapOf("type" to "itv", "action" to "get_genres"))
        session.shutdown()
        val p = profile()
        assertEquals("device_id", "DEV1", p.url.queryParameter("device_id"))
        assertEquals("device_id2", "DEV2", p.url.queryParameter("device_id2"))
        assertEquals("signature", "SIG", p.url.queryParameter("signature"))
        assertEquals("stb_type", "MAG322", p.url.queryParameter("stb_type"))
        assertEquals("hw_version", "2.6-IB-00", p.url.queryParameter("hw_version"))
        assertEquals("x-ua", "Model: MAG322; Link: WiFi", p.headers["X-User-Agent"])
        val genres = requests.first { it.url.queryParameter("action") == "get_genres" }
        assertEquals("content calls present the same box", "Model: MAG322; Link: WiFi", genres.headers["X-User-Agent"])
    }

    @Test
    fun `a rejected pinned identity fails without trying other boxes`() = runBlocking {
        profileBody = "Authorization failed."
        val session = StalkerSession(account(stbModel = "MAG254"), OkHttpClient())
        try {
            session.request(mapOf("type" to "itv", "action" to "get_genres"))
            fail("a rejected identity must surface")
        } catch (e: StalkerAuthException) {
            // expected
        } finally {
            session.shutdown()
        }
        val types = requests.filter { it.url.queryParameter("action") == "get_profile" }
            .map { it.url.queryParameter("stb_type") }
        assertTrue("only the pinned box may be presented: $types", types.isNotEmpty() && types.all { it == "MAG254" })
    }
}
