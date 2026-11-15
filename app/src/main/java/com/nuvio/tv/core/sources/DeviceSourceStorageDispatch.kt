package com.nuvio.tv.core.sources

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [DeviceSourceEngine] work that commits durably (DevicePreferenceStorage.write ->
 * ProtectedProfilePreferences: SharedPreferences commit() + fsync, ~88 ms in the cold-start profile)
 * on [dispatcher] instead of the caller's thread. The commit stays synchronous and durable inside the
 * engine — never apply() — and a failed write still throws through to the caller.
 */
class DeviceSourceStorageDispatch(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun <T> run(block: suspend () -> T): T = withContext(dispatcher) { block() }
}

/**
 * Runs [work] (which commits) on the storage dispatcher, then — back on the caller (Main for the
 * settings dialog) — runs [onReady] if the engine ended ready. UI callbacks never run on IO.
 */
internal suspend fun DeviceSourceStorageDispatch.commitThenIfReady(
    engine: DeviceSourceEngine,
    work: suspend DeviceSourceEngine.() -> Unit,
    onReady: () -> Unit,
) {
    run { engine.work() }
    if (engine.state.value.ready) onReady()
}
