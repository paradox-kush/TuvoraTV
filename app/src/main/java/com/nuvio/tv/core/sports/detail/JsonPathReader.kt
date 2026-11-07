package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Kotlin port of the reference reader in research/sports-mapping/validate.py.
 *
 * Syntax: `a.b[0].c[*].d`, filter `[key=value]` (case-insensitive on the primitive text), last `[-1]`.
 * A missing step yields nothing; this never throws. One deliberate divergence from the Python
 * reference: a malformed path (characters that are not a token) yields nothing instead of being
 * skipped, so a typo in a pushed mapping blanks a block rather than returning the whole document.
 */
object JsonPathReader {
    data class Match(val value: JsonElement, val homeAway: String?)

    private val token = Regex("""\.?([A-Za-z0-9_]+)|\[(\*)]|\[(-?\d+)]|\[([A-Za-z0-9_]+)=([^\]]+)]""")

    private sealed interface Step {
        data class Key(val k: String) : Step
        data object Star : Step
        data class Index(val i: Int) : Step
        data class Filter(val k: String, val v: String) : Step
    }

    private fun parse(path: String): List<Step>? {
        val steps = ArrayList<Step>()
        var pos = 0
        while (pos < path.length) {
            val m = token.matchAt(path, pos) ?: return null
            val g = m.groups
            steps += when {
                g[1] != null -> Step.Key(g[1]!!.value)
                g[2] != null -> Step.Star
                g[3] != null -> Step.Index(g[3]!!.value.toIntOrNull() ?: return null)
                else -> Step.Filter(g[4]!!.value, g[5]!!.value)
            }
            pos = m.range.last + 1
        }
        return steps
    }

    /** Like [read] but each match also carries the nearest ancestor `homeAway` ("home"/"away"), if any. */
    fun readTracked(root: JsonElement?, path: String, initialSide: String? = null): List<Match> = try {
        if (root == null) emptyList() else {
            val steps = parse(path)
            if (steps == null) emptyList() else {
                var cur: List<Match> = listOf(Match(root, sideOf(root) ?: initialSide))
                for (step in steps) {
                    val next = ArrayList<Match>()
                    for (c in cur) step(c, step, next)
                    cur = next
                    if (cur.isEmpty()) break
                }
                cur
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun read(root: JsonElement?, path: String): List<JsonElement> = readTracked(root, path).map { it.value }

    fun first(root: JsonElement?, path: String): JsonElement? = readTracked(root, path).firstOrNull()?.value

    fun firstText(root: JsonElement?, path: String): String? =
        readTracked(root, path).firstNotNullOfOrNull { text(it.value) }

    /** Primitive text, or null for objects, arrays and JSON null. */
    fun text(el: JsonElement?): String? = (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun sideOf(el: JsonElement): String? =
        ((el as? JsonObject)?.get("homeAway") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun wrap(el: JsonElement, parent: Match) = Match(el, sideOf(el) ?: parent.homeAway)

    private fun step(c: Match, s: Step, out: MutableList<Match>) {
        val v = c.value
        when (s) {
            is Step.Key -> if (v is JsonObject) v[s.k]?.takeIf { it !is JsonNull }?.let { out += wrap(it, c) }
            Step.Star -> if (v is JsonArray) v.forEach { out += wrap(it, c) }
            is Step.Index -> if (v is JsonArray) {
                val i = if (s.i < 0) v.size + s.i else s.i
                if (i in v.indices) out += wrap(v[i], c)
            }
            is Step.Filter -> if (v is JsonArray) v.forEach { x ->
                if (x is JsonObject && (x[s.k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.equals(s.v, ignoreCase = true)) {
                    out += wrap(x, c)
                }
            }
        }
    }
}
