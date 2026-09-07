package com.nuvio.tv.core.iptv.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TV twin of the mobile/desktop IptvOverlayPushDedupPolicyTest — the pure `(kind, okey)` collapse that
 * keeps `sync_push_iptv_overlay` from aborting a whole batch with a 21000 duplicate-conflict.
 *
 * JUnit here, so the argument order is assertEquals(message, expected, actual).
 */
class IptvOverlayPushDedupPolicyTest {

    private fun row(kind: String, okey: String, updatedAt: Long, value: String = "{}") =
        OverlayPushRow(
            kind = kind,
            okey = okey,
            playlistId = null,
            valueJson = value,
            updatedAt = updatedAt,
            deleted = false,
        )

    @Test
    fun `two entries with same kind and okey collapse to one`() {
        val out = IptvOverlayPushDedupPolicy.dedupe(
            listOf(row("channel", "fp:v1:a", 1), row("channel", "fp:v1:a", 2)),
        )
        assertEquals("one (kind, okey) must yield exactly one push event", 1, out.size)
    }

    @Test
    fun `max updated_at wins`() {
        val out = IptvOverlayPushDedupPolicy.dedupe(
            listOf(
                row("channel", "fp:v1:a", 5, "{\"pinned\":true}"),
                row("channel", "fp:v1:a", 9, "{\"hidden\":true}"),
                row("channel", "fp:v1:a", 3, "{\"pinned\":false}"),
            ),
        )
        assertEquals("collapses to the single winner", 1, out.size)
        assertEquals("the freshest updated_at survives", 9L, out.single().updatedAt)
        assertEquals("the freshest value survives with it", "{\"hidden\":true}", out.single().valueJson)
    }

    @Test
    fun `distinct identities are all preserved in first-seen order`() {
        val input = listOf(
            row("channel", "fp:v1:a", 1),
            row("channel", "fp:v1:b", 1),
            row("category", "c:v1:x", 1),
        )
        val out = IptvOverlayPushDedupPolicy.dedupe(input)
        assertEquals("no distinct identity is dropped", 3, out.size)
        assertEquals(
            "surviving order is stable (first-seen)",
            listOf("fp:v1:a", "fp:v1:b", "c:v1:x"),
            out.map { it.okey },
        )
    }

    @Test
    fun `same okey under a different kind is not merged`() {
        val out = IptvOverlayPushDedupPolicy.dedupe(
            listOf(row("channel", "shared", 1), row("category", "shared", 2)),
        )
        assertEquals("kind is part of the identity, so both survive", 2, out.size)
    }

    @Test
    fun `tie on updated_at keeps the last occurrence`() {
        val out = IptvOverlayPushDedupPolicy.dedupe(
            listOf(
                row("channel", "fp:v1:a", 7, "{\"first\":true}"),
                row("channel", "fp:v1:a", 7, "{\"second\":true}"),
            ),
        )
        assertEquals("a tie still collapses to one", 1, out.size)
        assertEquals("on a tie the later element wins", "{\"second\":true}", out.single().valueJson)
    }
}
