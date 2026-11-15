package com.nuvio.tv.core.sources

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceSourcesHost drove DeviceSourceEngine.commit -> ProtectedProfilePreferences.write (a durable
 * SharedPreferences commit() + fsync, ~88 ms in the cold-start profile) on Main. The commit must
 * stay durable and keep surfacing failures, but run on the injected storage dispatcher.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceSourceStorageDispatchTest {

    private class CountingStorage : DevicePreferenceStorage {
        var writes = 0
        var failWith: Exception? = null
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            failWith?.let { throw it }
            writes++
            this.value = value
        }
    }

    private val source = DeviceSourceDescriptor("source", "iptv", "playlist-key", "My IPTV", true, 1)

    private fun localEngine(storage: DevicePreferenceStorage) = DeviceSourceEngine(
        store = storage,
        rpc = { _: String, _: JsonObject -> error("offline") },
        uuid = { "id" },
        credential = { "secret" },
        now = { 1_000L },
        platform = "android",
        localOnly = true,
    )

    @Test
    fun `commit does not run on the caller until the injected dispatcher runs it`() = runTest {
        val storage = CountingStorage()
        val engine = localEngine(storage)
        val dispatch = DeviceSourceStorageDispatch(StandardTestDispatcher(testScheduler))

        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            dispatch.run { engine.localSources(1, listOf(source)) }
        }
        assertEquals("writes on the calling thread before the storage dispatcher ran", 0, storage.writes)

        runCurrent()
        pending.await()
        assertEquals("writes once the storage dispatcher ran", 1, storage.writes)
        assertEquals("engine published the committed sources", listOf(source), engine.state.value.disk.sources[1])
    }

    @Test
    fun `a failed commit still surfaces as IOException and is not acknowledged`() = runTest {
        val storage = CountingStorage().apply { failWith = IOException("Unable to save protected credentials") }
        val engine = localEngine(storage)
        val dispatch = DeviceSourceStorageDispatch(StandardTestDispatcher(testScheduler))

        var thrown: Throwable? = null
        try {
            dispatch.run { engine.localSources(1, listOf(source)) }
        } catch (e: IOException) {
            thrown = e
        }
        assertTrue("the commit failure reaches the caller", thrown is IOException)
        assertEquals("a failed commit is never published", null, engine.state.value.disk.sources[1])
    }
}
