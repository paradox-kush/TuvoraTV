package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.Side
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Team colours made safe for the dark surface. Hex strings are 6 digits, lower case, without '#'. */
object TeamColor {
    private const val MIN_CONTRAST = 3.0
    private const val MAX_STEPS = 7 // 70 % toward white
    private const val DISTINCT_RGB = 60.0

    /**
     * [hex] mixed toward white in 10 % steps (at most 70 %) until its contrast against [surfaceHex] is at least 3.0; a colour that
     * already reads is returned unchanged. Null or malformed input -> null (the caller falls back to the theme accent for home,
     * a neutral border colour for away).
     */
    fun forDark(hex: String?, surfaceHex: String): String? {
        val c = parse(hex) ?: return null
        val s = parse(surfaceHex) ?: return null
        var best = c
        for (step in 0..MAX_STEPS) {
            val mixed = IntArray(3) { i -> (c[i] + (255 - c[i]) * step / 10.0).roundToInt().coerceIn(0, 255) }
            best = mixed
            if (contrast(mixed, s) >= MIN_CONTRAST) break
        }
        return best.joinToString("") { it.toString(16).padStart(2, '0') }
    }

    /** When the two results are closer than RGB distance 60 the away side uses its alternate colour (same rule), else null. */
    fun pair(home: Side, away: Side, surfaceHex: String): Pair<String?, String?> {
        val h = forDark(home.color, surfaceHex)
        val a = forDark(away.color, surfaceHex)
        if (h == null || a == null) return h to a
        return if (distance(parse(h)!!, parse(a)!!) < DISTINCT_RGB) h to forDark(away.altColor, surfaceHex) else h to a
    }

    private fun parse(hex: String?): IntArray? {
        val t = hex?.trim()?.removePrefix("#") ?: return null
        if (t.length != 6 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return IntArray(3) { t.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    private fun luminance(c: IntArray): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])
    }

    fun contrast(a: IntArray, b: IntArray): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    fun contrastOf(aHex: String, bHex: String): Double = contrast(parse(aHex)!!, parse(bHex)!!)

    private fun distance(a: IntArray, b: IntArray): Double =
        sqrt((0 until 3).sumOf { ((a[it] - b[it]) * (a[it] - b[it])).toDouble() })
}
