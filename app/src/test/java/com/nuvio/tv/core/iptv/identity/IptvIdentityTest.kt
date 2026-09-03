package com.nuvio.tv.core.iptv.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canon-v1 cross-language PARITY test (TV twin). Expected ids come from research/canon-v1/canon_v1.mjs,
 * the SAME oracle NuvioMobile's IptvIdentityTest and the website's canonV1.test.ts assert against. TV
 * JUnit ordering: assertEquals(message, expected, actual). GENERATED (research/canon-v1); do not hand-edit.
 */
class IptvIdentityTest {

    private data class V(val playlistId: String, val name: String, val tvgId: String?, val canon: String, val entityId: String)

    private val golden = listOf(
        V("http://p|u", "BBC One HD", "BBCOne.uk", "bbc one hd", "fp:v1:e55ce7edec20b239c248ef06432d164b"),
        V("http://p|u", "bbc.one|hd", "BBCOne.uk", "bbc one hd", "fp:v1:e55ce7edec20b239c248ef06432d164b"),
        V("http://p|u", "BBC One FHD", "BBCOne.uk", "bbc one fhd", "fp:v1:414a3080e15b6ec5f6de53424aa7dc61"),
        V("http://p|u", "BBC ONE 4K", "BBCOne.uk", "bbc one 4k", "fp:v1:c662340d8ea844e9d9841dcea87b8c1b"),
        V("http://p|u", "BBC One HD", null, "bbc one hd", "fp:v1:690eff9e6f6fcd8686c62d49285859e9"),
        V("http://p|u", "  BBC   One   HD  ", null, "bbc one hd", "fp:v1:690eff9e6f6fcd8686c62d49285859e9"),
        V("http://p|u", "T\u00e9l\u00e9 Mont\u00e9-Carlo", null, "tele monte carlo", "fp:v1:eea61c82cd024590d21760a18241808d"),
        V("http://p|u", "TELE MONTE CARLO", null, "tele monte carlo", "fp:v1:eea61c82cd024590d21760a18241808d"),
        V("http://p|u", "\u0420\u043e\u0441\u0441\u0438\u044f 1", null, "\u0440\u043e\u0441\u0441\u0438\u044f 1", "fp:v1:9552cc980c4d47015c87f588979448dc"),
        V("http://p|u", "\u0420\u041e\u0421\u0421\u0418\u042f 1", null, "\u0440\u043e\u0441\u0441\u0438\u044f 1", "fp:v1:9552cc980c4d47015c87f588979448dc"),
        V("http://p|u", "\u03a3\u039a\u0391\u03aa HD", null, "\u03c3\u03ba\u03b1\u03b9 hd", "fp:v1:fb50d7897686a64d85f8a06e5b62d8ea"),
        V("http://p|u", "\uff2e\uff28\uff2b", null, "nhk", "fp:v1:54da7fb416b3d717e998c66c0cc1ddcd"),
        V("http://p|u", "NHK", null, "nhk", "fp:v1:54da7fb416b3d717e998c66c0cc1ddcd"),
        V("http://p|u", "\u0642\u0646\u0627\u0629 \u0627\u0644\u062c\u0632\u064a\u0631\u0629", null, "\u0642\u0646\u0627\u0629 \u0627\u0644\u062c\u0632\u064a\u0631\u0629", "fp:v1:00284663a96aacca7a7396664021407b"),
        V("http://p|u", "Sky Sports F1 UHD", "sky.f1", "sky sports f1 uhd", "fp:v1:31dddca14438ea4bb78cbcb7d82e9978"),
    )

    @Test
    fun `canon and entityId match the cross-language golden vectors`() {
        for (v in golden) {
            assertEquals("canon mismatch for ${v.name}", v.canon, IptvIdentity.canon(v.name))
            assertEquals("entityId mismatch for ${v.name}", v.entityId, IptvIdentity.entityId(v.playlistId, v.name, v.tvgId))
            assertTrue("ids carry their canon version: ${v.entityId}", v.entityId.startsWith("fp:v1:"))
        }
    }

    @Test
    fun `case separator accents cyrillic fullwidth fold to one identity`() {
        assertEquals("case+separator", golden[0].entityId, golden[1].entityId)
        assertEquals("whitespace", golden[4].entityId, golden[5].entityId)
        assertEquals("accents", golden[6].entityId, golden[7].entityId)
        assertEquals("cyrillic", golden[8].entityId, golden[9].entityId)
        assertEquals("fullwidth", golden[11].entityId, golden[12].entityId)
    }

    @Test
    fun `sibling quality variants stay distinct`() {
        assertNotEquals("HD vs FHD", golden[0].entityId, golden[2].entityId)
        assertNotEquals("HD vs 4K", golden[0].entityId, golden[3].entityId)
    }

    @Test
    fun `categoryKey is deterministic version-tagged and scoped`() {
        val a = IptvIdentity.categoryKey("http://p|u", "live", "UK | Entertainment")
        assertEquals("category name folds through canon", a, IptvIdentity.categoryKey("http://p|u", "live", "uk entertainment"))
        assertTrue("category keys are version-tagged: $a", a.startsWith("c:v1:"))
        assertNotEquals("scoped by content type", a, IptvIdentity.categoryKey("http://p|u", "movies", "UK | Entertainment"))
    }
}
