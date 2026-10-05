package com.nuvio.tv.core.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * B03 (D4) — field-level three-way merge of a settings blob, so a device that missed a website edit no
 * longer reverts it by pushing its whole stale blob on its next local change.
 *
 *  - [base]: the blob as this device last synced it (applied from the server, or pushed);
 *  - [local]: the blob as this device has it now;
 *  - [remote]: the server's blob right before the push.
 *
 * Objects merge key by key, recursively; any other value is a leaf: the LOCAL value wins only where this
 * device changed it since [base], otherwise the REMOTE value stands (an edit made elsewhere survives).
 * A key one side lacks counts as null. No [base] (first sync on this device) degrades to the old
 * whole-blob behaviour: local wins. Pure; twin: NuvioMobile/NuvioDesktop `core/sync/SettingsBlobMerge.kt`.
 */
object SettingsBlobMerge {

    fun merge(base: JsonElement?, local: JsonElement, remote: JsonElement?): JsonElement {
        if (base == null || remote == null) return local
        return mergeNode(base, local, remote) ?: local
    }

    private fun mergeNode(base: JsonElement?, local: JsonElement?, remote: JsonElement?): JsonElement? {
        if (local is JsonObject && remote is JsonObject) {
            val b = base as? JsonObject
            val keys = LinkedHashSet<String>().apply { addAll(remote.keys); addAll(local.keys) }
            val out = LinkedHashMap<String, JsonElement>()
            for (k in keys) mergeNode(b?.get(k), local[k], remote[k])?.let { out[k] = it }
            return JsonObject(out)
        }
        return if (local != base) local else remote
    }
}
