package com.nuvio.tv.core.mediaserver.api

import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps.reconcile
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps.recordAdd
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps.recordDelete
import com.nuvio.tv.core.mediaserver.api.MediaServerPendingOps.recordUpdate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class MediaServerPendingOpsTest {
    private fun e(id: String, name: String = id, enabled: Boolean = true, address: String? = "http://$id:8096") = MediaServerEntry(
        key = "jellyfin|m$id|u$id", type = MediaServerType.JELLYFIN, machineId = "m$id", userId = "u$id", name = name,
        address = address, enabled = enabled,
    )

    private val a = e("a")
    private val b = e("b")
    private val c = e("c")

    @Test
    fun anAddSurvivesReconcileOntoAnyBaseline() {
        assertEquals(listOf(a, b, c), reconcile(listOf(a, b), emptyList<MediaServerPendingOp>().recordAdd(c)))
        assertEquals(listOf(c), reconcile(emptyList(), emptyList<MediaServerPendingOp>().recordAdd(c)))
    }

    @Test
    fun aDeleteRemovesTheRowAndNeverResurrectsIt() {
        val ops = emptyList<MediaServerPendingOp>().recordDelete(b.key)
        assertEquals(listOf(a), reconcile(listOf(a, b), ops))
        assertEquals("already gone on the server: still gone", listOf(a), reconcile(listOf(a), ops))
    }

    @Test
    fun anAddThenDeleteInOneSessionCollapsesToNothing() {
        val ops = emptyList<MediaServerPendingOp>().recordAdd(c).recordDelete(c.key)
        assertTrue("net no-op: nothing to push, nothing to delete on a server that never had it", ops.isEmpty())
    }

    @Test
    fun anUpdateOfARowAnotherDeviceDeletedIsDropped() {
        val ops = emptyList<MediaServerPendingOp>().recordUpdate(a.copy(name = "renamed"), base = a)
        assertEquals("no zombie row", listOf(b), reconcile(listOf(b), ops))
    }

    @Test
    fun anUpdateOfAnAddStaysAnAdd() {
        val ops = emptyList<MediaServerPendingOp>().recordAdd(c).recordUpdate(c.copy(name = "C2"))
        assertEquals(1, ops.size)
        assertEquals("add", ops.single().kind)
        assertEquals(listOf(a, c.copy(name = "C2")), reconcile(listOf(a), ops))
    }

    @Test
    fun aFieldEditOnlyOverridesTheFieldsThisDeviceChanged() {
        // another device renamed "a" while this device disabled it: both survive
        val serverRow = a.copy(name = "Renamed elsewhere")
        val ops = emptyList<MediaServerPendingOp>().recordUpdate(a.copy(enabled = false), base = a)
        val merged = reconcile(listOf(serverRow), ops).single()
        assertEquals("Renamed elsewhere", merged.name)
        assertEquals(false, merged.enabled)
    }

    @Test
    fun theFirstBaseIsKeptAcrossRepeatedEdits() {
        val ops = emptyList<MediaServerPendingOp>()
            .recordUpdate(a.copy(name = "x"), base = a)
            .recordUpdate(a.copy(name = "y", enabled = false), base = a.copy(name = "x"))
        assertEquals(a, ops.single().base)
    }

    @Test
    fun theAddressToggleIsCompared_asWhatTheServerWouldStore() {
        // turning the toggle OFF clears the server's address even though the typed address is unchanged
        val off = a.copy(syncAddress = false)
        val ops = emptyList<MediaServerPendingOp>().recordUpdate(off, base = a)
        val merged = reconcile(listOf(a), ops).single()
        assertEquals(null, MediaServerSyncCodec.syncedAddress(merged))
    }

    @Test
    fun applyingRemoteKeepsWhatOnlyThisDeviceKnows() {
        val local = a.copy(userName = "kid", homeRows = setOf(MediaServerHomeRow.NEXT_UP), syncAddress = false, address = "http://typed-here:8096")
        val remote = a.copy(name = "Renamed", address = null)
        val applied = MediaServerPendingOps.applyRemote(listOf(remote, b), listOf(local)).first()
        assertEquals("synced field: server wins", "Renamed", applied.name)
        assertEquals("the server row has no address: keep the typed one", "http://typed-here:8096", applied.address)
        assertEquals("kid", applied.userName)
        assertEquals(setOf(MediaServerHomeRow.NEXT_UP), applied.homeRows)
        assertEquals(false, applied.syncAddress)
    }

    @Test
    fun applyingRemoteDropsLocalRowsTheServerNoLongerHas() {
        assertEquals(listOf(b), MediaServerPendingOps.applyRemote(listOf(b), listOf(a, b)))
    }
}
