package com.nuvio.tv.core.sports.detail.screen

import com.nuvio.tv.core.sports.detail.Side
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Team colours made safe for the dark surface. Hex strings are 6 digits, lower case, without '#'. */
object TeamColor {
    private const val MIN_CONTRAST = 3.0
    private const val STEP = 0.02          // lightness step
    private const val MAX_LIGHTNESS = 0.85 // never walks into white
    private const val VERY_DARK = 0.20     // a primary this dark needs a heavy lift: trade it for an alt that already reads
    private const val DISTINCT_RGB = 60.0

    /**
     * [hex] lightened in HSL (hue and saturation kept, so a navy stays a blue, not a grey) in 2 % steps up to lightness 85 % until its
     * contrast against [surfaceHex] is at least 3.0; a colour that already reads is returned unchanged. When the primary is very dark
     * (lightness < 20 %) and [altHex] reads on the surface as it is, the alt colour is used instead. Null or malformed input -> null
     * (the caller falls back to the theme accent for home, a neutral border colour for away).
     */
    fun forDark(hex: String?, surfaceHex: String, altHex: String? = null): String? {
        val c = parse(hex) ?: return null
        val s = parse(surfaceHex) ?: return null
        if (contrast(c, s) >= MIN_CONTRAST) return fmt(c)
        val (h, sat, l0) = toHsl(c)
        var l = l0
        var best = c
        while (l < MAX_LIGHTNESS) {
            l = minOf(MAX_LIGHTNESS, l + STEP)
            best = fromHsl(h, sat, l)
            if (contrast(best, s) >= MIN_CONTRAST) break
        }
        val alt = parse(altHex)
        if (alt != null && l0 < VERY_DARK && contrast(alt, s) >= MIN_CONTRAST) return fmt(alt)
        return fmt(best)
    }

    /** When the two results are closer than RGB distance 60 the away side uses its alternate colour (same rule), else null. */
    fun pair(home: Side, away: Side, surfaceHex: String): Pair<String?, String?> {
        val h = forDark(home.color, surfaceHex, home.altColor)
        val a = forDark(away.color, surfaceHex, away.altColor)
        if (h == null || a == null) return h to a
        return if (distance(parse(h)!!, parse(a)!!) < DISTINCT_RGB) h to forDark(away.altColor, surfaceHex) else h to a
    }

    private fun fmt(c: IntArray) = c.joinToString("") { it.toString(16).padStart(2, '0') }

    private fun toHsl(c: IntArray): Triple<Double, Double, Double> {
        val r = c[0] / 255.0; val g = c[1] / 255.0; val b = c[2] / 255.0
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2; val d = mx - mn
        if (d == 0.0) return Triple(0.0, 0.0, l)
        val sat = d / (1 - kotlin.math.abs(2 * l - 1))
        val h = (when (mx) { r -> (((g - b) / d) % 6 + 6) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 }) * 60
        return Triple(h, sat, l)
    }

    private fun fromHsl(h: Double, s: Double, l: Double): IntArray {
        val k = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = k * (1 - kotlin.math.abs((h / 60) % 2 - 1)); val m = l - k / 2
        val (r, g, b) = when ((h / 60).toInt().coerceIn(0, 5)) { 0 -> Triple(k, x, 0.0); 1 -> Triple(x, k, 0.0); 2 -> Triple(0.0, k, x); 3 -> Triple(0.0, x, k); 4 -> Triple(x, 0.0, k); else -> Triple(k, 0.0, x) }
        return intArrayOf(((r + m) * 255).roundToInt().coerceIn(0, 255), ((g + m) * 255).roundToInt().coerceIn(0, 255), ((b + m) * 255).roundToInt().coerceIn(0, 255))
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
