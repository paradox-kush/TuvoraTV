package com.nuvio.tv.core.iptv.stalker

import com.google.gson.JsonElement

/**
 * What a Stalker/Ministra portal says it can serve — UX147. `get_modules` was requested and its
 * answer thrown away, so the hub showed a Series tab for portals that report the module off.
 *
 * Pure, and honest about not knowing: every decoder returns null / false on anything it does not
 * positively recognise, and the caller keeps today's behaviour (show the tab) for unknown. A module
 * being off is NOT proof of "no series" on Ministra, which serves series through `vod` rows flagged
 * `is_series` ([StalkerSeriesDialect]) — so [seriesAbsent] also needs the dialect probe's verdict.
 */
object StalkerCapabilities {

    private const val SERIES = "series"

    /**
     * `get_modules` js → is the series module reported OFF? True only when `series` is listed under
     * `disabled_modules` / `restricted_modules`; false when the payload is understood and lists it as
     * available; null (unknown) for any other shape, including malformed.
     */
    fun seriesModuleDisabled(js: JsonElement?): Boolean? {
        if (js == null || !js.isJsonObject) return null
        val obj = js.asJsonObject
        val off = listOf("disabled_modules", "restricted_modules").mapNotNull { names(obj.get(it)) }
        if (off.any { SERIES in it }) return true
        val all = names(obj.get("all_modules"))
        val understood = off.isNotEmpty() || all != null
        if (!understood) return null
        return if (all != null && SERIES !in all && off.isEmpty()) null else false
    }

    private fun names(e: JsonElement?): Set<String>? = when {
        e == null -> null
        e.isJsonArray -> e.asJsonArray.mapNotNull {
            (if (it.isJsonPrimitive) it.asString else null)?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() }
        }.toSet()
        e.isJsonObject -> e.asJsonObject.keySet().map { it.trim().lowercase() }.toSet()
        else -> null
    }

    /**
     * The portal has NO series, as positive evidence for hiding the tab. Never from the module flag
     * alone (Ministra): the dialect probe must also have found nothing —
     *  - [vodFallbackHasSeries]: the `vod` probe found rows, i.e. Ministra — series ARE there;
     *  - [xcEmptySection]: the portal itself answered "nothing for this section" to `type=series`;
     *  - [xcAnsweredEmpty] with [modulesSeriesDisabled] == true: it answered with an empty list and had
     *    said the module is off.
     * A network/auth failure ([xcFailedOtherwise]) is never evidence.
     */
    fun seriesAbsent(
        modulesSeriesDisabled: Boolean?,
        xcEmptySection: Boolean,
        xcAnsweredEmpty: Boolean,
        xcFailedOtherwise: Boolean,
        vodFallbackHasSeries: Boolean,
    ): Boolean = when {
        vodFallbackHasSeries || xcFailedOtherwise -> false
        xcEmptySection -> true
        else -> xcAnsweredEmpty && modulesSeriesDisabled == true
    }
}
