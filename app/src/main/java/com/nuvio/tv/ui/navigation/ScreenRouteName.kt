package com.nuvio.tv.ui.navigation

import androidx.navigation.NavDestination

/**
 * The breadcrumb name of a screen: the static head of its route pattern, nothing else.
 *
 * `destination.route` is normally the pattern (`player/{streamUrl}/{title}?…`), but a route can be
 * built from concrete values, and the path/query after the head carries ids, titles and stream
 * URLs. Keeping only the first static segment (`player`, `detail`, `clean-live`) means no argument
 * can ever reach analytics or the persisted crash context, whatever a caller navigates with.
 */
object ScreenRouteName {
    private val STATIC_HEAD = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")

    fun of(route: String?, destinationId: Int): String {
        val head = route.orEmpty().substringBefore('?').substringBefore('/').substringBefore('#').trim()
        return if (STATIC_HEAD.matches(head)) head else "dest_$destinationId"
    }

    fun of(destination: NavDestination): String = of(destination.route, destination.id)
}
