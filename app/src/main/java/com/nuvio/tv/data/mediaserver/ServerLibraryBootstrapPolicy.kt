package com.nuvio.tv.data.mediaserver

/** One discovery per profile load. A legacy bridge may have cached only Collections. */
internal object ServerLibraryBootstrapPolicy {
    fun needsRefresh(
        connection: ServerConnection,
        hasToken: Boolean,
        availableOnDevice: Boolean = connection.availableOnDevice,
    ): Boolean = availableOnDevice && hasToken && (
        connection.libraries.isEmpty() ||
            (connection.managedBridgeId != null && (
                connection.libraries.none { it.holds(ServerMediaKind.MOVIE) } ||
                    connection.libraries.none { it.holds(ServerMediaKind.SERIES) }
                ))
        )
}
