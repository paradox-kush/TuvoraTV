package com.nuvio.tv.core.iptv

/**
 * Step 2 — the playlist DETAILS page's state, built purely so the TV screen (and the tests) hold no
 * logic: the read-only facts on the left and the three shelves of cards on the right.
 *
 * Shelves (decided design, "Option B"): PROVIDER (Contact), YOUR LIBRARY (Content & categories, Hidden
 * channels & groups, plus the playlist's own tuning and Disable), REMOVE (Detach for a managed playlist,
 * Remove for every playlist). An empty shelf is not built — there is never a dead stop.
 */
object ManagedDetailsModel {

    enum class DetailsAction {
        CONTACT, CONTENT, HIDDEN, REMATCH, CATCHUP, EDIT, REIMPORT, TOGGLE_ENABLED, DETACH, REMOVE
    }

    enum class ShelfGroup { PROVIDER, LIBRARY, REMOVE }

    data class Shelf(val group: ShelfGroup, val cards: List<DetailsAction>)

    sealed interface Expiry {
        /** [fraction] fills the thin bar: 1.0 at [BAR_FULL_DAYS] or more days left, shrinking toward 0. */
        data class Days(val daysLeft: Int, val fraction: Float) : Expiry
        data object Expired : Expiry
        /** The panel reports `exp_date` 0: the subscription does not end. No bar. */
        data object NeverExpires : Expiry
        /** A source that gives free text instead of an epoch (a Stalker portal): shown verbatim, no bar. */
        data class Text(val text: String) : Expiry
        /** The provider reports no expiry: "Expiry not reported by this provider" and NO bar. */
        data object NotReported : Expiry
    }

    data class Connections(val active: Int?, val max: Int)

    data class Facts(
        val name: String,
        /** The provider's name when this playlist is managed ("Managed by <provider>"). */
        val managedBy: String?,
        val expiry: Expiry,
        /** "N of M" when the panel reports both; null otherwise. */
        val connections: Connections?,
        /** A line of catalog counts ("12,000 channels"), when known. */
        val catalogLine: String?,
        /** True when the server and login are locked by the provider (the lock note shows). */
        val serverLoginLocked: Boolean,
    )

    /** "1 Oct" for the provider's last edit; null when the server does not say (omit "updated <date>"). */
    fun updatedLabel(
        iso: String?,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ): String? {
        // The server serialises timestamptz as "2026-10-02T07:04:50.515368+00:00": an offset, not "Z", which
        // Instant.parse does not take on Android's desugared java.time (found on the emulator).
        val instant = runCatching { java.time.OffsetDateTime.parse(iso ?: return null).toInstant() }.getOrNull() ?: return null
        return java.time.format.DateTimeFormatter.ofPattern("d MMM", locale).withZone(zone).format(instant)
    }

    const val BAR_FULL_DAYS = 30
    private const val DAY_SEC = 86_400L

    fun daysLeft(expiresAtEpochSec: Long, nowEpochSec: Long): Int {
        val remaining = expiresAtEpochSec - nowEpochSec
        if (remaining <= 0) return 0
        return ((remaining + DAY_SEC - 1) / DAY_SEC).toInt()
    }

    fun expiry(info: XtreamAccountInfo?, nowEpochSec: Long): Expiry {
        val epoch = info?.expiresAtEpochSec
        return when {
            !info?.expiresText.isNullOrBlank() -> Expiry.Text(info!!.expiresText!!)
            epoch == 0L -> Expiry.NeverExpires
            epoch != null && epoch > 0 -> {
                val days = daysLeft(epoch, nowEpochSec)
                if (days == 0) Expiry.Expired
                else Expiry.Days(days, (days.toFloat() / BAR_FULL_DAYS).coerceIn(0f, 1f))
            }
            else -> Expiry.NotReported
        }
    }

    fun facts(
        account: XtreamAccount,
        managed: ManagedPlaylistInfo?,
        info: XtreamAccountInfo?,
        catalogLine: String?,
        nowEpochSec: Long,
    ): Facts = Facts(
        name = account.name,
        managedBy = managed?.providerName,
        expiry = expiry(info, nowEpochSec),
        connections = info?.maxConnections?.takeIf { it > 0 }?.let { Connections(info.activeConnections, it) },
        catalogLine = catalogLine?.takeIf { it.isNotBlank() },
        serverLoginLocked = managed != null,
    )

    /**
     * The shelves for [account]. Contact shows only for a managed playlist whose provider set at least
     * one contact; Detach only for a managed one; Remove always. File playlists have no URL to edit:
     * re-picking the file is the edit (and the only action offered while the local copy is missing).
     */
    fun shelves(
        account: XtreamAccount,
        managed: ManagedPlaylistInfo?,
        needsReimport: Boolean,
    ): List<Shelf> {
        val library = buildList {
            if (needsReimport) {
                add(DetailsAction.REIMPORT)
            } else {
                add(DetailsAction.CONTENT)
                add(DetailsAction.HIDDEN)
                if (account.isXtream()) {
                    add(DetailsAction.REMATCH)
                    add(DetailsAction.CATCHUP)
                }
                // A managed playlist's server and login are the provider's: the lock note replaces Edit.
                if (ManagedPlaylistPolicy.showEditServerLogin(managed != null)) add(DetailsAction.EDIT)
            }
            add(DetailsAction.TOGGLE_ENABLED)
        }
        return buildList {
            if (managed != null && !managed.support.isEmpty) {
                add(Shelf(ShelfGroup.PROVIDER, listOf(DetailsAction.CONTACT)))
            }
            add(Shelf(ShelfGroup.LIBRARY, library))
            add(
                Shelf(
                    ShelfGroup.REMOVE,
                    if (managed != null) listOf(DetailsAction.DETACH, DetailsAction.REMOVE)
                    else listOf(DetailsAction.REMOVE)
                )
            )
        }
    }
}
