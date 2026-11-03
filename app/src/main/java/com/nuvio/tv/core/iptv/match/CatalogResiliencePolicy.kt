package com.nuvio.tv.core.iptv.match

/**
 * Pure decisions behind "Xtream matching survives a provider that can't serve its catalog"
 * (research/xtream-catalog-resilience-plan.md). Measured 2026-11-02 on real panels: a full
 * `get_series` answered 200 with a body cut off at 24 KB / 110 KB, or trickled at ~0.45 KB/s,
 * so the one-shot catalog the matcher depended on never landed and every title read "No streams".
 *
 * Twin of NuvioMobile's `CatalogResiliencePolicy` (same decisions, same constants).
 */
internal object CatalogResiliencePolicy {

    /** After a full list fails part-way, the provider is asked category by category for this long. */
    const val AVOID_FULL_MS = 6 * 60 * 60 * 1000L

    /**
     * P4 item 5. Whole-request deadlines. The 60 s per-read socket timeout never trips on a body that
     * trickles (~0.45 KB/s), so without these one dead panel holds a build slot for hours (P5 starved).
     * Per call, never client-wide: a legitimate full list streams for minutes.
     */
    const val FULL_LIST_DEADLINE_MS = 10 * 60_000L
    const val CATEGORY_DEADLINE_MS = 60_000L

    /** The deadline for one catalog request: the streamed full list or a single category. */
    fun deadlineMs(fullList: Boolean): Long = if (fullList) FULL_LIST_DEADLINE_MS else CATEGORY_DEADLINE_MS

    /** Pause between per-category requests, so a one-connection account isn't hammered. */
    const val CATEGORY_GAP_MS = 250L

    /**
     * P2. A title lookup never waits for a catalog download: it starts (or joins) the build and answers
     * from what is already local. Forced and awaited refreshes ("Refresh now", the scheduled worker) and
     * callers that explicitly need the index ([waitIfMissing]) still wait.
     */
    fun shouldAwaitBuild(existingBuiltAt: Long?, force: Boolean, awaitRefresh: Boolean, waitIfMissing: Boolean): Boolean =
        force || awaitRefresh || (existingBuiltAt == null && waitIfMissing)

    enum class Remembered {
        /** The mapped row is in the local index: use it. */
        USE_ROW,

        /** No complete local catalog to check against: trust the verified sid (P1). */
        USE_SID,

        /** A fresh "not on this provider" that the catalog hasn't contradicted since. */
        NOT_ON_PROVIDER,

        /** Nothing usable: match by name. */
        UNKNOWN,
    }

    /**
     * P1. What a remembered (`tmdb_map`, possibly synced from another device) mapping is worth.
     * [catalogComplete] is false while the index is missing OR partial: a sid that a complete catalog
     * no longer lists is stale, but one a partial catalog hasn't reached yet is not.
     */
    fun remembered(
        sid: Int?,
        rowPresent: Boolean,
        catalogComplete: Boolean,
        updatedAtMs: Long,
        nowMs: Long,
        lastAddedAtMs: Long,
        negativeTtlMs: Long,
    ): Remembered = when {
        sid != null && rowPresent -> Remembered.USE_ROW
        sid != null && !catalogComplete -> Remembered.USE_SID
        sid != null -> Remembered.UNKNOWN // vanished from a complete catalog: re-match
        nowMs - updatedAtMs < negativeTtlMs && updatedAtMs > lastAddedAtMs -> Remembered.NOT_ON_PROVIDER
        else -> Remembered.UNKNOWN
    }

    /** P4. Try the one-shot full list, or go straight to categories because it failed recently. */
    fun useFullList(avoidFullUntilMs: Long?, nowMs: Long): Boolean = avoidFullUntilMs == null || nowMs >= avoidFullUntilMs

    enum class CategoryStep {
        /** Keep what arrived for this category and move on to the next. */
        SKIP_CATEGORY,

        /** The provider is refusing or overloaded: stop the walk and back off; progress is kept. */
        STOP_AND_BACK_OFF,
    }

    /**
     * P4. One category request failed. 429 / 5xx (Cloudflare's 520/524 included) and the host guard's fast
     * fail mean "stop asking"; anything else (a body cut off mid-JSON, one bad category) is that category only.
     */
    fun afterCategoryFailure(httpStatus: Int?, hostGuardRefused: Boolean): CategoryStep = when {
        hostGuardRefused -> CategoryStep.STOP_AND_BACK_OFF
        httpStatus == 429 -> CategoryStep.STOP_AND_BACK_OFF
        httpStatus != null && httpStatus >= 500 -> CategoryStep.STOP_AND_BACK_OFF
        else -> CategoryStep.SKIP_CATEGORY
    }

    /**
     * P4. A category walk may finalise the index (stamp it built, drop rows the panel no longer lists)
     * only when it saw every category in one pass. Otherwise the rows it got stay searchable as a
     * partial index, and nothing is deleted on the strength of an incomplete view.
     */
    fun walkCompletes(categoriesTotal: Int, categoriesDone: Int, startedAtCategory: Int): Boolean =
        categoriesTotal > 0 && startedAtCategory == 0 && categoriesDone == categoriesTotal

    /**
     * P5. How many catalog builds may run at once across all playlists. One per provider is enforced
     * separately; this cap keeps peak memory where the old single slot kept it on low-RAM devices.
     */
    fun globalBuildPermits(lowMemoryDevice: Boolean): Int = if (lowMemoryDevice) 1 else 2
}
