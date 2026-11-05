package com.nuvio.tv.core.iptv

/**
 * What the playlist details page does about the panel's account answer (B171). A playlist this device may use is
 * asked as always. One turned off HERE gets a single read-only inspection - except a Stalker portal: its handshake
 * can log another device out of a one-session portal, so it is only asked while turned on.
 */
object DetailsInspectionPolicy {
    enum class Plan { NORMAL, INSPECT, SKIP }

    fun plan(sourceType: String, availableHere: Boolean): Plan = when {
        availableHere -> Plan.NORMAL
        sourceType == XtreamAccount.SOURCE_STALKER -> Plan.SKIP
        else -> Plan.INSPECT
    }
}
