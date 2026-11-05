package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.M3UEntry
import com.nuvio.tv.core.iptv.content.M3UKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A playlist's catch-up attributes must survive ingest, or the guide can never offer a replay. */
class M3uIngestCatchUpTest {

    private fun entry(catchUp: M3uCatchUpInfo?, kind: M3UKind = M3UKind.LIVE) = M3UEntry(
        name = "BBC One", tvgId = "bbc1", tvgName = null, logo = null, group = "UK",
        url = "http://h/live/u/p/1.ts", kind = kind, ext = "ts", catchUp = catchUp,
    )

    @Test
    fun `a live channel keeps its catch-up settings`() {
        val info = M3uCatchUpInfo(M3uCatchUpType.SHIFT, "?utc={utc}", 3)
        val row = (M3uIngestMapping.map(entry(info), null) as M3uIngestRow.Channel).row
        assertEquals("catch-up carried onto the stored row", info, row.catchUp)
        assertTrue("hasArchive follows catch-up", row.hasArchive)
    }

    @Test
    fun `a live channel without catch-up has none`() {
        val row = (M3uIngestMapping.map(entry(null), null) as M3uIngestRow.Channel).row
        assertNull("no catch-up", row.catchUp)
        assertEquals("no archive", false, row.hasArchive)
    }
}
