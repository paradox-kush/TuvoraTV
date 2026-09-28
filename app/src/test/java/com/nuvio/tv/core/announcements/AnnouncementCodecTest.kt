package com.nuvio.tv.core.announcements

import com.nuvio.tv.domain.model.Announcement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the `get_app_announcements` RPC body and of the on-device cache (same JSON shape). */
class AnnouncementCodecTest {

    private val rpcBody = """
        [
          {"id":"11111111-1111-1111-1111-111111111111","title":"New in 1.9","body":"Faster guide.",
           "cta_label":"Read more","cta_url":"https://tuvora.co/news","kind":"update",
           "starts_at":"2026-09-27T10:00:00+00:00"},
          {"id":"22222222-2222-2222-2222-222222222222","title":"Terms updated","body":"We updated our Terms.",
           "cta_label":null,"cta_url":null,"kind":"policy","starts_at":"2026-09-26T10:00:00+00:00"}
        ]
    """.trimIndent()

    @Test
    fun `parses the rpc response with snake_case fields and nulls in server order`() {
        val items = AnnouncementCodec.decode(rpcBody)
        assertEquals("count", 2, items.size)
        val first = items[0]
        assertEquals("id", "11111111-1111-1111-1111-111111111111", first.id)
        assertEquals("title", "New in 1.9", first.title)
        assertEquals("body", "Faster guide.", first.body)
        assertEquals("cta label", "Read more", first.ctaLabel)
        assertEquals("cta url", "https://tuvora.co/news", first.ctaUrl)
        assertEquals("kind", "update", first.kind)
        assertEquals("starts at", "2026-09-27T10:00:00+00:00", first.startsAt)
        assertEquals("second keeps order", "22222222-2222-2222-2222-222222222222", items[1].id)
        assertEquals("null cta label", null, items[1].ctaLabel)
        assertEquals("null cta url", null, items[1].ctaUrl)
    }

    @Test
    fun `ignores unknown fields so the server can add columns`() {
        val items = AnnouncementCodec.decode("""[{"id":"a","title":"T","body":"B","priority":5,"extra":{"x":1}}]""")
        assertEquals("parsed despite unknown fields", listOf("a"), items.map { it.id })
    }

    @Test
    fun `a malformed row is skipped without dropping the others`() {
        val items = AnnouncementCodec.decode(
            """[{"title":"no id"},{"id":"ok","title":"Fine"},"garbage",{"id":"n","title":null}]"""
        )
        assertEquals("only the valid row survives", listOf("ok"), items.map { it.id })
    }

    @Test
    fun `empty array is an empty list`() {
        assertTrue(AnnouncementCodec.decode("[]").isEmpty())
    }

    @Test(expected = Exception::class)
    fun `a non-array body is a failure so the cache is kept`() {
        AnnouncementCodec.decode("""{"message":"function not found"}""")
    }

    @Test
    fun `encode then decode round-trips the cache`() {
        val items = listOf(
            Announcement("a", "T1", "B1", "Go", "https://tuvora.co", "info", "2026-09-27T00:00:00Z"),
            Announcement("b", "T2", "", null, null, "policy", null),
        )
        assertEquals("round trip", items, AnnouncementCodec.decode(AnnouncementCodec.encode(items)))
    }
}
