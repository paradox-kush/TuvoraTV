package com.nuvio.tv.core.sync

/** One row of the server's version vector (`sync_get_surface_versions`); [profileId] null = account-wide. */
data class SurfaceVersion(val profileId: Int?, val surface: String, val version: Long)

/**
 * B03 (D1/D2) — Realtime is a best-effort hint with no replay (Supabase: "on reconnect, always fetch
 * current state"), so after a (re)subscribe the device compares the server's per-surface version
 * vector with the versions it last pulled and pulls ONLY what advanced. An idle slate plans nothing:
 * the catch-up then costs the single < 1 KB vector read and no surface pull at all (delta, not dump).
 *
 * Seen versions are keyed per (profile, surface) — account-wide rows apart — and advance only for the
 * surfaces actually pulled, after the pull succeeded ([advance]). Pure; twin: NuvioMobile/
 * NuvioDesktop `core/sync/SurfaceCatchUpPolicy.kt` (same tests).
 */
object SurfaceCatchUpPolicy {

    fun key(profileId: Int?, surface: String): String = "${profileId ?: "*"}:$surface"

    fun seenOf(versions: Collection<SurfaceVersion>): Map<String, Long> =
        versions.associate { key(it.profileId, it.surface) to it.version }

    /** The rows of [server] whose surface this client can pull and whose version is ahead of [seen]. */
    fun plan(seen: Map<String, Long>, server: Collection<SurfaceVersion>, supported: Set<String>): List<SurfaceVersion> =
        server.filter { it.surface in supported && it.version > (seen[key(it.profileId, it.surface)] ?: 0L) }

    /** [seen] after [pulled] were pulled successfully (never moves a version backwards). */
    fun advance(seen: Map<String, Long>, pulled: Collection<SurfaceVersion>): Map<String, Long> {
        val out = seen.toMutableMap()
        for (p in pulled) {
            val k = key(p.profileId, p.surface)
            out[k] = maxOf(out[k] ?: 0L, p.version)
        }
        return out
    }
}

/**
 * B03 (D2) — channel supervision. A channel that reached SUBSCRIBED used to park forever even after the
 * server closed it (token expiry, standby); supabase-kt only rejoins channels that are already
 * SUBSCRIBED while the socket is CONNECTED. A channel out of SUBSCRIBED for longer than [GRACE_MS]
 * (room for the client's own rejoin) is removed and recreated, and every SUBSCRIBED catches up.
 */
object RealtimeChannelHealthPolicy {
    const val GRACE_MS: Long = 20_000L

    enum class Action { Stay, Resubscribe }

    fun decide(subscribed: Boolean, notSubscribedSinceMs: Long?, nowMs: Long): Action =
        if (subscribed || notSubscribedSinceMs == null || nowMs - notSubscribedSinceMs < GRACE_MS) Action.Stay
        else Action.Resubscribe
}
