package com.nuvio.tv.domain.model

/**
 * Which Continue Watching order a profile gets when it never picked one.
 *
 * Owner decision 2026-11-24: "Pick up where I left off" is the default for NEW installs only.
 * Existing users keep recency order, and an explicit choice always wins.
 *
 * Pure: the cohort is decided once per install (see CwOrderCohortStore) from facts the caller reads.
 */
object CwOrderDefaultPolicy {

    enum class Cohort { NEW_INSTALL, EXISTING_INSTALL }

    /**
     * Decided once, on the first launch of the build that ships this feature. An install that already
     * had a layout chosen, or any stored order, was in use before the feature existed.
     */
    fun cohortFor(layoutAlreadyChosen: Boolean, hasStoredOrder: Boolean): Cohort =
        if (layoutAlreadyChosen || hasStoredOrder) Cohort.EXISTING_INSTALL else Cohort.NEW_INSTALL

    /**
     * @param stored the persisted enum name, or null when the profile never chose.
     * @param cohort null while the cohort is not decided yet: stay on the old default, never flip it.
     */
    fun resolve(stored: String?, cohort: Cohort?): ContinueWatchingSortMode {
        val explicit = stored?.let { name -> ContinueWatchingSortMode.entries.firstOrNull { it.name == name } }
        if (explicit != null) return explicit
        return if (cohort == Cohort.NEW_INSTALL) ContinueWatchingSortMode.RESUME_FIRST else ContinueWatchingSortMode.DEFAULT
    }
}
