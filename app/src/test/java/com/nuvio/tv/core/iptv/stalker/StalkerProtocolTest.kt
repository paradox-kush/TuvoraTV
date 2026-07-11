package com.nuvio.tv.core.iptv.stalker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-helper tests for the Stalker protocol (no network). MAC matches the validated test portal. */
class StalkerProtocolTest {

    private val mac = "00:1A:79:58:B3:A6"

    @Test
    fun `device identity derives sn from md5 and deviceId from sha256, deterministic`() {
        val id = StalkerProtocol.deriveDeviceIdentity(mac)
        // sn = md5(mac).hex.upper()[:13]  (md5 hex is 32 chars -> take 13)
        assertEquals(13, id.serialNumber.length)
        assertEquals(id.serialNumber, id.serialNumber.uppercase())
        // deviceId = deviceId2 = sha256(mac).hex.upper()  (64 hex chars)
        assertEquals(64, id.deviceId.length)
        assertEquals(id.deviceId, id.deviceId2)
        assertEquals(64, id.signature.length)
        // deterministic for the same MAC
        assertEquals(id.serialNumber, StalkerProtocol.deriveDeviceIdentity(mac).serialNumber)
        assertEquals(id.deviceId, StalkerProtocol.deriveDeviceIdentity(mac).deviceId)
    }

    @Test
    fun `serial and device id overrides win over the mac-derived values`() {
        val id = StalkerProtocol.deriveDeviceIdentity(mac, serialOverride = "MYSERIAL123", deviceIdOverride = "MYDEVICE")
        assertEquals("MYSERIAL123", id.serialNumber)
        assertEquals("MYDEVICE", id.deviceId)
        // signature folds in the overridden values, so it differs from the pure-MAC identity
        assertTrue(id.signature != StalkerProtocol.deriveDeviceIdentity(mac).signature)
    }

    @Test
    fun `extractStreamUrl strips every known launcher prefix down to the URL`() {
        val url = "http://host:8080/live/u/p/745149.ts?play_token=abc"
        assertEquals(url, StalkerProtocol.extractStreamUrl("ffmpeg $url"))
        assertEquals(url, StalkerProtocol.extractStreamUrl("auto $url"))
        assertEquals(url, StalkerProtocol.extractStreamUrl("ffrt3 $url"))
        assertEquals(url, StalkerProtocol.extractStreamUrl(url))            // already bare
        assertNull(StalkerProtocol.extractStreamUrl(null))
        assertNull(StalkerProtocol.extractStreamUrl("no url here"))
    }

    @Test
    fun `mac cookie encoding replaces colons`() {
        assertEquals("00%3A1A%3A79%3A58%3AB3%3AA6", StalkerProtocol.encodeMacForCookie(mac))
    }

    @Test
    fun `normalize portal base reduces any pasted url to origin`() {
        val origin = "http://host:8080"
        // Whatever STB-UI path / trailing junk the user pastes, we probe from the origin.
        assertEquals(origin, StalkerProtocol.normalizePortalBase("$origin/c/"))
        assertEquals(origin, StalkerProtocol.normalizePortalBase("$origin/c/index.html"))
        assertEquals(origin, StalkerProtocol.normalizePortalBase("$origin/portal.php"))
        assertEquals(origin, StalkerProtocol.normalizePortalBase("$origin/"))
        assertEquals(origin, StalkerProtocol.normalizePortalBase(origin))            // already bare
        assertEquals(origin, StalkerProtocol.normalizePortalBase("$origin/c/index.html?foo=1"))
        assertEquals("http://host:8080", StalkerProtocol.normalizePortalBase("host:8080/c/"))   // no scheme -> http
        assertEquals("https://host", StalkerProtocol.normalizePortalBase("https://host/stalker_portal/c/"))
    }
}
