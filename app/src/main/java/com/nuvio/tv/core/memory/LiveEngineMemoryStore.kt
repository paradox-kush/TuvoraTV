package com.nuvio.tv.core.memory

import android.content.Context
import com.nuvio.tv.core.analytics.LiveEngineMemory
import com.nuvio.tv.core.analytics.LiveRecoveryCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists [LiveEngineMemory] across app restarts (design §3.7) — so a channel the recovery
 * coordinator escalated to libmpv stays remembered and opens directly on mpv next launch, not just
 * this session. The pure decision + cache live in [LiveEngineMemory]; this is only the storage
 * effect behind its [LiveEngineMemory.restore]/[LiveEngineMemory.onChange] seam.
 *
 * Eager-injected in `NuvioApplication` so the cache is loaded BEFORE the first read (the guide's
 * tune path / the main player's engine resolution). SharedPreferences is read synchronously, which
 * is what lets those synchronous reads see the persisted state without an async load race. The set
 * is tiny (only MPV entries — EXO is the unlearned default), so a full-snapshot rewrite is cheap.
 */
@Singleton
class LiveEngineMemoryStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        val restored = prefs.all.mapNotNull { (key, value) ->
            val engine = (value as? String)?.let {
                runCatching { LiveRecoveryCoordinator.Engine.valueOf(it) }.getOrNull()
            }
            if (engine != null) key to engine else null
        }.toMap()
        LiveEngineMemory.restore(restored)
        LiveEngineMemory.onChange = {
            val snapshot = LiveEngineMemory.snapshot()
            prefs.edit().clear().apply { snapshot.forEach { (key, engine) -> putString(key, engine.name) } }.apply()
        }
    }

    private companion object {
        const val PREFS = "live_engine_memory"
    }
}
