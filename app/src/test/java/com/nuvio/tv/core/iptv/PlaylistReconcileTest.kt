package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B24 §4 (TV) — the pure reconcile + pull-classification core. Proves the conflict path preserves the
 * user's intent onto the authoritative server baseline (no wipe, no resurrection) and that remote
 * absence is only ever inferred from a SUCCESSFUL, genuinely-empty read.
 */
class PlaylistReconcileTest {

    private fun acc(id: String, name: String = "P") =
        XtreamAccount(id = id, name = name, baseUrl = "http://$id", username = "u", password = "p")

    @Test
    fun `reset then add-C onto a server holding A and B preserves A and B`() {
        val result = reconcilePendingOntoBaseline(listOf(acc("A"), acc("B")), listOf(PendingPlaylistOp.Add(acc("C"))))
        assertEquals("add-C reconciles to A,B,C — not a wipe", listOf("A", "B", "C"), result.accounts.map { it.id })
        assertTrue(result.droppedUpdateIds.isEmpty())
    }

    @Test
    fun `a delete is not resurrected by the baseline`() {
        val result = reconcilePendingOntoBaseline(listOf(acc("A"), acc("B")), listOf(PendingPlaylistOp.Delete("A")))
        assertEquals("delete-A stays deleted after reconcile", listOf("B"), result.accounts.map { it.id })
    }

    @Test
    fun `an edit is re-applied to the matching baseline row`() {
        val result = reconcilePendingOntoBaseline(listOf(acc("A"), acc("B", "Old")), listOf(PendingPlaylistOp.Update(acc("B", "New"))))
        assertEquals("the edit re-applies onto the server row", "New", result.accounts.first { it.id == "B" }.name)
        assertEquals(2, result.accounts.size)
        assertTrue(result.droppedUpdateIds.isEmpty())
    }

    @Test
    fun `an edit of a server-deleted row is dropped and surfaced not resurrected`() {
        val result = reconcilePendingOntoBaseline(listOf(acc("A")), listOf(PendingPlaylistOp.Update(acc("B", "New"))))
        assertEquals("the deleted row is not re-added", listOf("A"), result.accounts.map { it.id })
        assertEquals("the dropped edit is surfaced", listOf("B"), result.droppedUpdateIds)
    }

    @Test
    fun `multiple ops replay in order`() {
        val result = reconcilePendingOntoBaseline(
            listOf(acc("A"), acc("B")),
            listOf(PendingPlaylistOp.Add(acc("C")), PendingPlaylistOp.Delete("A"), PendingPlaylistOp.Update(acc("B", "B2")))
        )
        assertEquals("A removed, C added", listOf("B", "C"), result.accounts.map { it.id }.sorted())
        assertEquals("B2", result.accounts.first { it.id == "B" }.name)
    }

    @Test
    fun `add of an existing id upserts rather than duplicating`() {
        val result = reconcilePendingOntoBaseline(listOf(acc("A", "Old")), listOf(PendingPlaylistOp.Add(acc("A", "New"))))
        assertEquals("no duplicate id", 1, result.accounts.size)
        assertEquals("New", result.accounts.single().name)
    }

    @Test
    fun `a failed pull is indeterminate regardless of the values`() {
        assertEquals(PlaylistPullOutcome.Indeterminate, classifyPlaylistPull(false, emptyList(), 0))
        assertEquals(PlaylistPullOutcome.Indeterminate, classifyPlaylistPull(false, listOf(acc("A")), 5))
        assertFalse("a timeout/permission error must never authorize a fresh create", classifyPlaylistPull(false, emptyList(), 0).permitsFreshCreation())
    }

    @Test
    fun `a successful empty read at revision zero is authoritatively absent`() {
        val outcome = classifyPlaylistPull(true, emptyList(), 0)
        assertEquals(PlaylistPullOutcome.AuthoritativeAbsent, outcome)
        assertTrue("only a proven-empty server permits a fresh create", outcome.permitsFreshCreation())
    }

    @Test
    fun `a successful read with rows is present and does not permit a blind create`() {
        val outcome = classifyPlaylistPull(true, listOf(acc("A")), 3)
        assertTrue(outcome is PlaylistPullOutcome.Present)
        assertEquals(3L, (outcome as PlaylistPullOutcome.Present).revision)
        assertFalse("a populated server must be reconciled onto", outcome.permitsFreshCreation())
    }

    @Test
    fun `rows present at revision zero are still Present not Absent - legacy-seeded rows`() {
        val outcome = classifyPlaylistPull(true, listOf(acc("A")), 0)
        assertTrue("rows at rev 0 are Present, not Absent", outcome is PlaylistPullOutcome.Present)
        assertFalse(outcome.permitsFreshCreation())
    }
}
