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
        CONTACT, CONTENT, HIDDEN, GUIDE, REMATCH, CATCHUP, EDIT, REIMPORT, TOGGLE_ENABLED, DETACH, REMOVE
    }

    enum class ShelfGroup { PROVIDER, LIBRARY, REMOVE }

    data class Shelf(val group: ShelfGroup, val cards: List<DetailsAction>)

    sealed interface Expiry {
        /**
         * [daysLeft] whole days left. [fraction] fills the thin bar: shown ONLY for the last [BAR_FULL_DAYS] days
         * (full at 30, shrinking to the end date); null above 30 days = "N days left" as text, no bar.
         */
        data class Days(val daysLeft: Int, val fraction: Float?) : Expiry
        data object Expired : Expiry
        /** The panel reports `exp_date` 0: the subscription does not end. No bar. */
        data object NeverExpires : Expiry
        /** A source that gives free text instead of an epoch (a Stalker portal): shown verbatim, no bar. */
        data class Text(val text: String) : Expiry
        /** The panel answered and reported no expiry: "Expiry not reported by this provider". No bar. */
        data object NotReported : Expiry
        /** The panel was asked and did not answer: "Couldn't check expiry". Never "not reported". */
        data object CheckFailed : Expiry
        /** Not asked yet (or a source with no account endpoint): nothing is said about expiry. */
        data object Unknown : Expiry
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

    /** The smallest visible bar (a fraction), so the last day still shows something. */
    const val BAR_MIN_FRACTION = 0.04f

    fun expiry(info: XtreamAccountInfo?, nowEpochSec: Long, checkFailed: Boolean = false): Expiry {
        if (info == null) return if (checkFailed) Expiry.CheckFailed else Expiry.Unknown
        val epoch = info.expiresAtEpochSec
        return when {
            !info.expiresText.isNullOrBlank() -> Expiry.Text(info.expiresText)
            epoch == 0L -> Expiry.NeverExpires
            epoch != null && epoch > 0 -> {
                val days = daysLeft(epoch, nowEpochSec)
                when {
                    days == 0 -> Expiry.Expired
                    days > BAR_FULL_DAYS -> Expiry.Days(days, null)
                    else -> Expiry.Days(days, (days.toFloat() / BAR_FULL_DAYS).coerceIn(BAR_MIN_FRACTION, 1f))
                }
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
        checkFailed: Boolean = false,
    ): Facts = Facts(
        name = account.name,
        managedBy = managed?.providerName,
        expiry = expiry(info, nowEpochSec, checkFailed),
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
                // Lane G (B10/F10/F14): every playlist type has a guide and channel names.
                add(DetailsAction.GUIDE)
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

/**
 * The catalog counts line ("12,000 channels"): a count is shown only when the index/ingest has produced it and it is
 * above zero. A zero reads as "this provider has none", which is a claim we cannot make until the catalog is known.
 */
object CatalogCountsPolicy {
    fun parts(channels: Int?, movies: Int?, series: Int?): List<String> = buildList {
        channels?.takeIf { it > 0 }?.let { add("$it channels") }
        movies?.takeIf { it > 0 }?.let { add("$it movies") }
        series?.takeIf { it > 0 }?.let { add("$it series") }
    }
}
