package com.nuvio.tv.core.iptv.match

/**
 * A playlist could not answer a title lookup because its catalog has not landed on this device yet
 * (still downloading, or the panel cut it off). Shown as "still loading", never as "no streams":
 * a failure is never "empty" (bounded-load rule). Twin of Mobile's IptvCatalogPendingException.
 */
class IptvCatalogPendingException(val playlistName: String) :
    IllegalStateException("$playlistName is still loading its catalog")
