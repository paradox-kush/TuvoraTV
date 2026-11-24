package com.nuvio.tv.core.telemetry

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The one-time first-run funnel steps (`first_run_step` event, contract
 * research/first-run-funnel-2026-11/CONTRACT.md). Each fires at most once per install; the once-gate, the
 * new-vs-existing decision and the consent rule live in [FirstRunFunnelService], so a call site is one line.
 * The TV twin of NuvioMobile's `core/telemetry/api/FirstRunStep.kt` + `internal/FirstRunFunnel.kt`.
 */
enum class FirstRunStep(val wire: String) {
    FIRST_LAUNCH("first_launch"),
    SOURCE_ADDED("source_added"),
    FIRST_CONTENT_LOADED("first_content_loaded"),
    FIRST_PLAYBACK("first_playback"),

    /** The first visit ended (the whole app left the foreground) before anything played. Fired by the service, never by a call site. */
    FIRST_LEAVE("first_leave"),
}

/** Closed vocabularies for `source_kind` and `via`. Anything else is sent as `unknown`. */
object FirstRunVocab {
    const val KIND_M3U = "m3u"
    const val KIND_XTREAM = "xtream"
    const val KIND_STALKER = "stalker"
    const val KIND_ADDON = "addon"
    const val KIND_MEDIA_SERVER = "media_server"
    const val KIND_UNKNOWN = "unknown"
    val sourceKinds: Set<String> = setOf(KIND_M3U, KIND_XTREAM, KIND_STALKER, KIND_ADDON, KIND_MEDIA_SERVER, KIND_UNKNOWN)

    const val VIA_MANUAL = "manual"
    const val VIA_SETUP_CODE = "setup_code"
    const val VIA_SYNC = "sync"
    const val VIA_UNKNOWN = "unknown"
    val vias: Set<String> = setOf(VIA_MANUAL, VIA_SETUP_CODE, VIA_SYNC, VIA_UNKNOWN)

    /**
     * Closed vocabulary of `save_error`: the lower-cased names of [com.nuvio.tv.core.iptv.PlaylistSaveError], `other` for
     * a failure outside it, `none` when no save failed (a test pins this to the enum so the two cannot drift).
     */
    const val SAVE_ERROR_OTHER = "other"
    const val SAVE_ERROR_NONE = "none"
    val saveErrors: Set<String> = setOf(
        "missing_xtream_fields", "invalid_address", "unreachable", "wrong_credentials", "provider_blocked",
        "secure_connection_failed", "account_inactive", "source_disabled_on_device", "source_disabled_for_account",
        "signing_in", SAVE_ERROR_OTHER,
    )

    /** Maps a playlist `sourceType` (TV: `xtream`, `url`, `file`, `stalker`; KMP spellings too) to a [sourceKinds] value. */
    fun kindOfPlaylist(sourceType: String?): String = when (sourceType) {
        "xtream" -> KIND_XTREAM
        "url", "file", "m3u_url", "m3u_file" -> KIND_M3U
        "stalker" -> KIND_STALKER
        else -> KIND_UNKNOWN
    }
}

/**
 * Persisted funnel state. [existing] = an install that predates the feature: all steps count as done, nothing is
 * ever sent. [done] holds [FirstRunStep.wire] names.
 */
data class FirstRunState(
    val existing: Boolean,
    val firstLaunchAtMs: Long = 0L,
    val launchIndex: Int = 0,
    val done: Set<String> = emptySet(),
    /** First-visit facts for `first_leave`; persisted because the form may be opened in one launch and left in another. */
    val openedSourceForm: Boolean = false,
    val triedSave: Boolean = false,
    val saveError: String? = null,
)

/**
 * Facts about the first visit that [FirstRunStep.FIRST_LEAVE] summarises. Closed shapes only: a screen identifier
 * from the app's own route names, never anything the person typed.
 */
sealed interface FirstRunNote {
    /** A screen was shown ([name] = the `Breadcrumbs.screenChanged` identifier; reduced to the fixed vocabulary by the policy). */
    data class Screen(val name: String) : FirstRunNote

    /** An add-source form (Xtream / M3U / Stalker / add-on install / media server) was opened. */
    data object SourceFormOpened : FirstRunNote

    /** The person pressed save/add on such a form. */
    data object SaveTried : FirstRunNote

    /** A save failed; [kind] is the playlist-save error name lower-cased (anything outside the vocabulary becomes `other`). */
    data class SaveFailed(val kind: String) : FirstRunNote
}

/** [state] to persist (null = nothing to persist) and the event props to emit (null = send nothing). */
class FirstRunTransition(val state: FirstRunState?, val emit: Map<String, Any>?)

/**
 * The first-run funnel decisions. Pure: no I/O, no clock, no SDK, so it tests without the network.
 */
object FirstRunFunnel {
    const val EVENT = "first_run_step"
    const val SCHEMA_VERSION = 1

    /**
     * Once per process start. With no persisted state the install is decided here, once: [existingInstall] is only
     * asked then. An existing install persists "all done" and never emits; a new one persists its first-launch time
     * and `launch_index = 1` and emits `first_launch`. With state, a new install's `launch_index` goes up by one.
     */
    fun start(state: FirstRunState?, nowMs: Long, existingInstall: () -> Boolean): FirstRunTransition {
        if (state == null) {
            if (existingInstall()) return FirstRunTransition(FirstRunState(existing = true), null)
            val fresh = FirstRunState(
                existing = false,
                firstLaunchAtMs = nowMs,
                launchIndex = 1,
                done = setOf(FirstRunStep.FIRST_LAUNCH.wire),
            )
            return FirstRunTransition(fresh, props(fresh, FirstRunStep.FIRST_LAUNCH, nowMs, emptyMap()))
        }
        if (state.existing) return FirstRunTransition(null, null)
        return FirstRunTransition(state.copy(launchIndex = state.launchIndex + 1), null)
    }

    /** A step happened. Emits it the first time on a new install, never otherwise (also before [start]: dropped). */
    fun record(state: FirstRunState?, step: FirstRunStep, nowMs: Long, extra: Map<String, Any>): FirstRunTransition {
        if (state == null || state.existing || step.wire in state.done) return FirstRunTransition(null, null)
        // Someone who already played is not a drop-off: no leave summary (and nothing to mark done).
        if (step == FirstRunStep.FIRST_LEAVE && FirstRunStep.FIRST_PLAYBACK.wire in state.done) return FirstRunTransition(null, null)
        val next = state.copy(done = state.done + step.wire)
        return FirstRunTransition(next, props(next, step, nowMs, extra))
    }

    /**
     * A first-visit fact. Returns the state to persist, or null when nothing changes: no state yet, an existing install,
     * the leave gate already closed (it fired, or playback happened), an unchanged flag, or a note that is not persisted
     * (a screen lives in the per-process visit).
     */
    fun note(state: FirstRunState?, note: FirstRunNote): FirstRunState? {
        if (state == null || state.existing) return null
        if (FirstRunStep.FIRST_LEAVE.wire in state.done || FirstRunStep.FIRST_PLAYBACK.wire in state.done) return null
        val next = when (note) {
            is FirstRunNote.Screen -> return null
            FirstRunNote.SourceFormOpened -> state.copy(openedSourceForm = true)
            FirstRunNote.SaveTried -> state.copy(triedSave = true)
            is FirstRunNote.SaveFailed ->
                state.copy(saveError = note.kind.lowercase().takeIf { it in FirstRunVocab.saveErrors } ?: FirstRunVocab.SAVE_ERROR_OTHER)
        }
        return next.takeIf { it != state }
    }

    private fun props(state: FirstRunState, step: FirstRunStep, nowMs: Long, extra: Map<String, Any>): Map<String, Any> =
        LinkedHashMap<String, Any>().apply {
            put("step", step.wire)
            put("schema_version", SCHEMA_VERSION)
            put("launch_index", state.launchIndex)
            put("secs_since_first_launch", ((nowMs - state.firstLaunchAtMs) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            when (step) {
                FirstRunStep.SOURCE_ADDED -> {
                    put("source_kind", closed(extra["source_kind"], FirstRunVocab.sourceKinds))
                    put("via", closed(extra["via"], FirstRunVocab.vias))
                }
                FirstRunStep.FIRST_CONTENT_LOADED -> token(extra["surface"])?.let { put("surface", it) }
                FirstRunStep.FIRST_PLAYBACK -> {
                    put("kind", closed(extra["kind"], setOf("live", "vod")))
                    token(extra["engine"])?.let { put("engine", it) }
                }
                FirstRunStep.FIRST_LAUNCH -> Unit
                FirstRunStep.FIRST_LEAVE -> {
                    put("last_screen", FirstLeavePolicy.screenId(extra["last_screen"] as? String))
                    put("secs_in_app", (extra["secs_in_app"] as? Int ?: 0).coerceAtLeast(0))
                    put("screens_seen", (extra["screens_seen"] as? Int ?: 0).coerceAtLeast(0))
                    put("opened_source_form", state.openedSourceForm)
                    put("tried_save", state.triedSave)
                    put("save_error", state.saveError ?: FirstRunVocab.SAVE_ERROR_NONE)
                }
            }
        }

    private fun closed(raw: Any?, allowed: Set<String>): String =
        (raw as? String)?.lowercase()?.takeIf { it in allowed } ?: "unknown"

    /** A code-like identifier (engine id, surface key): letters, digits and `_` only, so no URL or host can ride along. */
    private fun token(raw: Any?): String? =
        (raw as? String)?.lowercase()?.takeWhile { it.isLetterOrDigit() || it == '_' }?.take(24)?.ifEmpty { null }
}

/** [FirstRunState] <-> the one string kept in the telemetry prefs. A damaged blob reads as "no state". */
object FirstRunStateCodec {
    fun encode(state: FirstRunState): String = JsonObject().apply {
        addProperty("existing", state.existing)
        addProperty("firstLaunchAtMs", state.firstLaunchAtMs)
        addProperty("launchIndex", state.launchIndex)
        add("done", JsonArray().also { arr -> state.done.sorted().forEach { arr.add(it) } })
        addProperty("openedSourceForm", state.openedSourceForm)
        addProperty("triedSave", state.triedSave)
        state.saveError?.let { addProperty("saveError", it) }
    }.toString()

    fun decode(text: String?): FirstRunState? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val o = JsonParser.parseString(text).asJsonObject
            FirstRunState(
                existing = o.get("existing")?.asBoolean ?: return null,
                firstLaunchAtMs = o.get("firstLaunchAtMs")?.asLong ?: 0L,
                launchIndex = o.get("launchIndex")?.asInt ?: 0,
                done = o.get("done")?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }?.toSet().orEmpty(),
                // Absent in a state written before first_leave existed: default (false / none).
                openedSourceForm = o.get("openedSourceForm")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                triedSave = o.get("triedSave")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                saveError = o.get("saveError")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it in FirstRunVocab.saveErrors },
            )
        }.getOrNull()
    }
}

/** Pure reads of stored blobs for the existing-install check (the storage reads themselves live in [FirstRunExistingInstallProbe]). */
object FirstRunInstallSignals {
    /** A playlist store (a JSON array of accounts) with at least one row. A damaged, non-empty blob counts: something was saved. */
    fun playlistsPresent(raw: String?): Boolean {
        val t = raw?.trim().orEmpty()
        return t.isNotEmpty() && t != "[]"
    }

    /** A watch-progress payload (a JSON object keyed by item) or other JSON that holds at least one entry. An empty shell does not count. */
    fun hasEntries(raw: String?): Boolean {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return false
        val element = runCatching { JsonParser.parseString(t) }.getOrNull() ?: return true // unreadable but present: something was saved
        return hasEntries(element)
    }

    private fun hasEntries(e: JsonElement): Boolean = when {
        e.isJsonArray -> e.asJsonArray.size() > 0
        e.isJsonObject -> e.asJsonObject.size() > 0
        else -> false
    }

    /** An add-on is the user's when it is not one of the factory [defaults] (compared without a trailing slash or `/manifest.json`). */
    fun userAddonUrls(urls: Collection<String>, defaults: Collection<String>): List<String> {
        val factory = defaults.map(::normalizeAddonUrl).toSet()
        return urls.filter { it.isNotBlank() && normalizeAddonUrl(it) !in factory }
    }

    private fun normalizeAddonUrl(url: String): String =
        url.trim().trimEnd('/').removeSuffix("/manifest.json").trimEnd('/').lowercase()
}
