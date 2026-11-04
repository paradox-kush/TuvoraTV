package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Links saved by builds before the addon-name/logo/description fields must still decode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StreamLinkCacheDecodeTest {
    private val now = 1_000_000L

    @Test
    fun legacyEntryWithoutNewFieldsDecodesWithNullDefaults() {
        val legacy = """{"url":"https://cdn.example/a.mkv","streamName":"A","cachedAtMs":999000,
            "deviceSourceKind":"addon","deviceSourceId":"addon:x","audioDelayMs":120}"""
        val link = decodeCachedStreamLink(legacy, 10_000L, now)
        assertNotNull("legacy entry still decodes", link)
        assertEquals("url kept", "https://cdn.example/a.mkv", link!!.url)
        assertEquals("device provenance kept", "addon", link.deviceSourceKind)
        assertNull("new addonName defaults to null", link.addonName)
        assertNull("new addonLogo defaults to null", link.addonLogo)
        assertNull("new streamDescription defaults to null", link.streamDescription)
    }

    @Test
    fun newFieldsRoundTrip() {
        val json = """{"url":"https://cdn.example/a.mkv","streamName":"A","cachedAtMs":999000,
            "addonName":"Prov","addonLogo":"https://l/x.png","streamDescription":"1080p"}"""
        val link = decodeCachedStreamLink(json, 10_000L, now)
        assertEquals("addon name", "Prov", link?.addonName)
        assertEquals("description", "1080p", link?.streamDescription)
    }

    @Test
    fun expiredOrMalformedEntriesAreNull() {
        assertNull("expired", decodeCachedStreamLink("""{"url":"u","streamName":"A","cachedAtMs":1}""", 10_000L, now))
        assertNull("malformed", decodeCachedStreamLink("not json", 10_000L, now))
    }
}
