package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.contracts.PlaybackPlayMethod

/**
 * What the mint step learned about a stream it handed to the player, kept so the session reporter can tell the
 * server the TRUE play method and the negotiated session/source ids without the player knowing any of it
 * (design 5.7: the reporter fills `playMethod`). In memory only - a session never outlives the process.
 */
internal object MediaServerPlaybackSessions {
    data class Session(
        val serverKey: String,
        val itemId: String,
        val mediaSourceId: String?,
        val playSessionId: String?,
        val playMethod: PlaybackPlayMethod,
    )

    private val lock = Any()
    private val byItem = mutableMapOf<String, Session>()
    private val latestByServer = mutableMapOf<String, Session>()

    fun record(session: Session) = synchronized(lock) {
        byItem[key(session.serverKey, session.itemId)] = session
        latestByServer[session.serverKey] = session
    }

    fun forItem(serverKey: String, itemId: String): Session? = synchronized(lock) { byItem[key(serverKey, itemId)] }

    /** The most recently minted session of a server - how a matched-lane play (whose video id is a TMDB id) finds its item. */
    fun latestFor(serverKey: String): Session? = synchronized(lock) { latestByServer[serverKey] }

    fun reset() = synchronized(lock) {
        byItem.clear()
        latestByServer.clear()
    }

    private fun key(serverKey: String, itemId: String) = "$serverKey|$itemId"
}
