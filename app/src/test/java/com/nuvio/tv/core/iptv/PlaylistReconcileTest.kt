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

    // --- B60: replace (id-changing edit) + field-level merge ----------------------------------------

    @Test
    fun `a replace swaps the old id for the new one in place`() {
        val result = reconcilePendingOntoBaseline(
            listOf(acc("A"), acc("B")),
            listOf(PendingPlaylistOp.Replace(oldId = "A", account = acc("A2"), base = acc("A"))),
        )
        assertEquals("old id removed, new id at its position", listOf("A2", "B"), result.accounts.map { it.id })
        assertTrue(result.droppedUpdateIds.isEmpty())
    }

    @Test
    fun `a replace whose old row was deleted elsewhere is kept as the user's add`() {
        val result = reconcilePendingOntoBaseline(
            listOf(acc("B")),
            listOf(PendingPlaylistOp.Replace(oldId = "A", account = acc("A2"), base = acc("A"))),
        )
        assertEquals("the user typed this playlist; it is not dropped", listOf("B", "A2"), result.accounts.map { it.id })
    }

    @Test
    fun `a replace of the only playlist never yields an empty set`() {
        val result = reconcilePendingOntoBaseline(
            listOf(acc("A")),
            listOf(PendingPlaylistOp.Replace(oldId = "A", account = acc("A2"), base = acc("A"))),
        )
        assertEquals(listOf("A2"), result.accounts.map { it.id })
    }

    @Test
    fun `an update with a base applies only the fields the user changed`() {
        val base = acc("A")
        val merged = mergeEditOntoServer(base.copy(userAgent = "X", name = "Server name"), base, base.copy(autoRefreshHours = 6))
        assertEquals("the edited field wins", 6, merged.autoRefreshHours)
        assertEquals("an untouched field keeps the server's value", "X", merged.userAgent)
        assertEquals("an untouched field keeps the server's value", "Server name", merged.name)
    }

    @Test
    fun `an update without a base replaces the whole row - older pending logs`() {
        val result = reconcilePendingOntoBaseline(
            listOf(acc("A").copy(userAgent = "X")),
            listOf(PendingPlaylistOp.Update(acc("A", name = "New"))),
        )
        assertEquals("New", result.accounts.single().name)
        assertEquals("no base = legacy whole-row semantics", null, result.accounts.single().userAgent)
    }

    @Test
    fun `a replace is recorded as one op carrying the old id`() {
        val ops = emptyList<PendingOpDto>().recordReplace("A", acc("A2"), base = acc("A"))
        assertEquals(listOf(Triple("replace", "A2", "A")), ops.map { Triple(it.kind, it.id, it.oldId) })
    }

    @Test
    fun `a replace of a locally added playlist stays an add`() {
        val ops = emptyList<PendingOpDto>().recordAdd(acc("A")).recordReplace("A", acc("A2"), base = acc("A"))
        assertEquals(listOf("add" to "A2"), ops.map { it.kind to it.id })
    }

    @Test
    fun `a replace chain collapses to the first old id and the first base`() {
        val ops = emptyList<PendingOpDto>()
            .recordReplace("A", acc("B"), base = acc("A"))
            .recordReplace("B", acc("C"), base = acc("B"))
        assertEquals(listOf(Triple("replace", "C", "A")), ops.map { Triple(it.kind, it.id, it.oldId) })
        assertEquals("the base stays the last-synced row", "A", ops.single().base?.id)
    }

    @Test
    fun `deleting a replaced playlist deletes the old id`() {
        val ops = emptyList<PendingOpDto>().recordReplace("A", acc("B"), base = acc("A")).recordDelete("B")
        assertEquals(listOf("delete" to "A"), ops.map { it.kind to it.id })
    }

    @Test
    fun `an update after a replace keeps the replace`() {
        val ops = emptyList<PendingOpDto>()
            .recordReplace("A", acc("B"), base = acc("A"))
            .recordUpdate(acc("B", name = "Renamed"), base = acc("B"))
        assertEquals(listOf(Triple("replace", "B", "A")), ops.map { Triple(it.kind, it.id, it.oldId) })
        assertEquals("Renamed", ops.single().account?.name)
    }

    @Test
    fun `an older pending log without base or old id still decodes`() {
        val raw = """{"revision":3,"pending":[{"kind":"update","id":"A","account":{"id":"A","name":"P","baseUrl":"http://A","username":"u","password":"p"}}]}"""
        val ops = decodePlaylistSyncState(com.google.gson.Gson(), raw).pending.toOps()
        assertEquals(1, ops.size)
        val op = ops.single() as PendingPlaylistOp.Update
        assertEquals("A", op.account.id)
        assertEquals("no base = whole-row semantics", null, op.base)
    }
}
