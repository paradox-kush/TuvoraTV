package com.nuvio.tv.core.mediaserver.flow

import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.FakeClient
import com.nuvio.tv.core.mediaserver.M
import com.nuvio.tv.core.mediaserver.TestRig
import com.nuvio.tv.core.mediaserver.client.HealthStatus
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.entry
import com.nuvio.tv.core.mediaserver.store.StoredCredential
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MediaServerListControllerTest {
    private val fake = FakeClient()

    @Test
    fun aHealthCheckIsAskedOncePerVisitAndNotDuplicatedWhileRunning() = runTest {
        var calls = 0
        val rig = TestRig(clientFactory = { object : com.nuvio.tv.core.mediaserver.client.MediaServerClient by fake {
            override suspend fun me(): com.nuvio.tv.core.mediaserver.client.mediabrowser.UserDto { calls++; return fake.me() }
        } })
        val e = entry()
        rig.store.applyFromRemote(1, listOf(e))
        rig.credentials.save(e.serverKey, StoredCredential("T"))
        val c = MediaServerListController(rig.services, this)
        c.checkOnce(listOf(e)); c.checkOnce(listOf(e))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(HealthStatus.ONLINE, c.healthState.value[e.serverKey])
        assertTrue(c.checkingState.value.isEmpty())
        val rows = c.rows(listOf(e), rig.services.expiredSessions.value, c.healthState.value, c.checkingState.value)
        assertEquals(ServerStatus.SIGNED_IN, rows.single().status)
    }

    @Test
    fun anEntryThatIsNotSignedInIsNeverAskedAnything() = runTest {
        val rig = TestRig(http = com.nuvio.tv.core.mediaserver.FakeHttp { error("no request expected") })
        val e = entry()
        rig.store.applyFromRemote(1, listOf(e))
        val c = MediaServerListController(rig.services, this)
        c.checkOnce(listOf(e)); advanceUntilIdle()
        assertTrue(c.healthState.value.isEmpty())
        assertEquals(ServerStatus.NEEDS_SIGN_IN, c.rows(listOf(e), emptySet(), emptyMap(), emptySet()).single().status)
    }
}
