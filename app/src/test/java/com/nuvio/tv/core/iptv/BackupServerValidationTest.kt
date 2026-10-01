package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs [BackupServerGolden] (Step 0.3) — the SAME cases as NuvioMobile/NuvioDesktop commonTest and
 * nuvio-web's backupServers.golden.json. The golden table names source types in the KMP vocabulary
 * ("m3u_url"/"m3u_file"); TV stores them as "url"/"file", so this runner maps the name — the table
 * itself stays byte-identical across repos.
 */
class BackupServerValidationTest {

    private fun tvSourceType(golden: String): String = when (golden) {
        "m3u_url" -> XtreamAccount.SOURCE_URL
        "m3u_file" -> XtreamAccount.SOURCE_FILE
        else -> golden   // "xtream" / "stalker" are spelled the same on TV
    }

    @Test
    fun `golden validation table`() {
        for (case in BackupServerGolden.cases) {
            val outcome = BackupServerValidation.validate(tvSourceType(case.sourceType), case.main, case.entries)
            assertEquals("${case.name}: urls", case.expectedUrls, outcome.urls)
            assertEquals("${case.name}: problems", case.expectedProblems, outcome.problems.map { it.index to it.problem.name })
        }
    }

    @Test
    fun `backups exist for xtream and m3u links and stalker only`() {
        assertTrue(BackupServerValidation.supportsBackups(XtreamAccount.SOURCE_XTREAM))
        assertTrue(BackupServerValidation.supportsBackups(XtreamAccount.SOURCE_URL))
        assertTrue(BackupServerValidation.supportsBackups(XtreamAccount.SOURCE_STALKER))
        assertFalse(BackupServerValidation.supportsBackups(XtreamAccount.SOURCE_FILE))
    }
}

class BackupServerListEditsTest {

    @Test
    fun `add stops at five rows`() {
        var rows = emptyList<String>()
        repeat(7) { rows = BackupServerListEdits.add(rows) }
        assertEquals(5, rows.size)
        assertFalse(BackupServerListEdits.canAdd(rows))
    }

    @Test
    fun `move up and down reorder priority and ignore the edges`() {
        val rows = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), BackupServerListEdits.moveUp(rows, 1))
        assertEquals(listOf("a", "c", "b"), BackupServerListEdits.moveDown(rows, 1))
        assertEquals(rows, BackupServerListEdits.moveUp(rows, 0))
        assertEquals(rows, BackupServerListEdits.moveDown(rows, 2))
    }

    @Test
    fun `remove and update touch only their row`() {
        val rows = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), BackupServerListEdits.remove(rows, 1))
        assertEquals(listOf("a", "x", "c"), BackupServerListEdits.update(rows, 1, "x"))
        assertEquals(rows, BackupServerListEdits.remove(rows, 9))
    }
}
