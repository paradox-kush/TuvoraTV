package com.nuvio.tv.core.mediaserver.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPickPolicyTest {
    private val item = "7c8cf7256b5c4167abfbd0711fa6a20c"

    @Test
    fun aFirstVersionStampedWithTheItemIdIsAskedForByItsOwnIdFromThePath() {
        // recorded: the item id streams the server's own choice, not the version the list showed first
        assertEquals("8e5ca1f5b4d858debaad8a62a3667978", VersionPickPolicy.pickId(item, item, "/remux/8e5ca1f5-b4d8-58de-baad-8a62a3667978/Release.1080p.BluRay"))
        assertEquals("8e5ca1f5b4d858debaad8a62a3667978", VersionPickPolicy.pickId(item, item, "/remux/8e5ca1f5-b4d8-58de-baad-8a62a3667978"))
        assertEquals("8e5ca1f5b4d858debaad8a62a3667978", VersionPickPolicy.pickId(item, item, "/remux/8e5ca1f5-b4d8-58de-baad-8a62a3667978.strm"))
    }

    @Test
    fun everyOtherVersionKeepsItsOwnId() {
        assertEquals("116bd7d401555be3a7c3ccea97f302c7", VersionPickPolicy.pickId(item, "116bd7d401555be3a7c3ccea97f302c7", "/remux/116bd7d4-0155-5be3-a7c3-ccea97f302c7/x"))
    }

    @Test
    fun aPlainLibrarysFirstVersionIsTheItemAndStaysSo() {
        assertEquals(item, VersionPickPolicy.pickId(item, item, "/media/movies/The Matrix (1999)/The Matrix (1999).mkv"))
        assertEquals(item, VersionPickPolicy.pickId(item, item, "/stub"))
        assertEquals(item, VersionPickPolicy.pickId(item, item, "https://cdn.example/media/test.mp4?v=1"))
        assertEquals(item, VersionPickPolicy.pickId(item, item, null))
        assertEquals(item, VersionPickPolicy.pickId(item, item, "/remux/not-a-uuid/file"))
    }

    @Test
    fun anAddOnResultsOwnWordsDescribeItsVersion() {
        assertEquals("Server A · 1080p · BluRay x264 12 GB", VersionPickPolicy.description("Server A\n1080p\nBluRay x264 12 GB", "1080p · H.264"))
        assertEquals(
            "[Service A] Release 2160p · Release.2160p.WEB.DL.HEVC.15.GB.mp4 · 💾1.18 MiB",
            VersionPickPolicy.description("[Service A] Release 2160p\nRelease.2160p.WEB.DL.HEVC.15.GB.mp4\n💾1.18 MiB", "4K · HEVC"),
        )
    }

    @Test
    fun aFileNameOrARepeatOfTheLabelAddsNothing() {
        assertNull(VersionPickPolicy.description("The Matrix (1999)", "1080p · H.264 · 5.4 GB"))
        assertNull(VersionPickPolicy.description(null, "1080p"))
        assertNull("an unprobed version already shows its name as the label", VersionPickPolicy.description("Server A\n1080p", "Server A · 1080p"))
    }

    @Test
    fun placeholdersAreRecognisedByTheirSourceType() {
        assertTrue(VersionPickPolicy.isPlaceholder("Placeholder"))
        assertTrue(VersionPickPolicy.isPlaceholder("placeholder"))
        assertFalse(VersionPickPolicy.isPlaceholder("Default"))
        assertFalse(VersionPickPolicy.isPlaceholder(null))
    }
}
