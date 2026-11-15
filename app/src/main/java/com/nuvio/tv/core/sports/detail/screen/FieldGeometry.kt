package com.nuvio.tv.core.sports.detail.screen

/**
 * Pure geometry of the football field strip. The feed's `yardLine` is yards from the HOME team's goal line (verified on live
 * captures 2026-11-14/15), so x = 0 is the home goal line and x = 1 the away goal line; home's end zone is drawn on the left.
 * Possession is "home" / "away" (the strings of the shared contract).
 */
object FieldGeometry {
    /** 0f = home goal line, 1f = away goal line. */
    fun x(yardLine: Int): Float = yardLine.coerceIn(0, 100) / 100f

    /** The side with the ball attacks the OTHER side's goal: home toward 1f, away toward 0f. */
    fun attackingToward(possession: String): Float = if (possession == "home") 1f else 0f

    /** The line to gain; null when down/distance or the ball are unknown, or it would reach or pass the goal line (goal-to-go). */
    fun firstDownX(yardLine: Int, distance: Int?, possession: String?): Float? {
        if (distance == null || distance <= 0) return null
        val target = when (possession) {
            "home" -> yardLine + distance
            "away" -> yardLine - distance
            else -> return null
        }
        return if (target >= 100 || target <= 0) null else x(target)
    }

    /** The shaded span from where the drive started to the ball, ordered (min, max); null without a start. */
    fun driveSpan(startYardLine: Int?, yardLine: Int): Pair<Float, Float>? {
        if (startYardLine == null) return null
        val a = x(startYardLine)
        val b = x(yardLine)
        return minOf(a, b) to maxOf(a, b)
    }
}
