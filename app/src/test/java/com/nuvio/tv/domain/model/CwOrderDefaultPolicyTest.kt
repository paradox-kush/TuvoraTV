package com.nuvio.tv.domain.model

import com.nuvio.tv.domain.model.CwOrderDefaultPolicy.Cohort
import org.junit.Assert.assertEquals
import org.junit.Test

/** JUnit argument order: assertEquals(message, expected, actual). */
class CwOrderDefaultPolicyTest {

    @Test
    fun `a fresh install is the new-install cohort`() {
        assertEquals(
            "fresh",
            Cohort.NEW_INSTALL,
            CwOrderDefaultPolicy.cohortFor(layoutAlreadyChosen = false, hasStoredOrder = false)
        )
    }

    @Test
    fun `an install that already chose a layout is an existing install`() {
        assertEquals(
            "layout chosen",
            Cohort.EXISTING_INSTALL,
            CwOrderDefaultPolicy.cohortFor(layoutAlreadyChosen = true, hasStoredOrder = false)
        )
    }

    @Test
    fun `an install with a stored order is an existing install`() {
        assertEquals(
            "stored order",
            Cohort.EXISTING_INSTALL,
            CwOrderDefaultPolicy.cohortFor(layoutAlreadyChosen = false, hasStoredOrder = true)
        )
    }

    @Test
    fun `new installs default to pick up where I left off`() {
        assertEquals(
            "new install",
            ContinueWatchingSortMode.RESUME_FIRST,
            CwOrderDefaultPolicy.resolve(null, Cohort.NEW_INSTALL)
        )
    }

    @Test
    fun `existing installs keep most recent first`() {
        assertEquals(
            "existing install",
            ContinueWatchingSortMode.DEFAULT,
            CwOrderDefaultPolicy.resolve(null, Cohort.EXISTING_INSTALL)
        )
    }

    @Test
    fun `an undecided cohort never flips the order`() {
        assertEquals(
            "undecided",
            ContinueWatchingSortMode.DEFAULT,
            CwOrderDefaultPolicy.resolve(null, null)
        )
    }

    @Test
    fun `an explicit choice always wins over the cohort`() {
        assertEquals(
            "explicit recency on a new install",
            ContinueWatchingSortMode.DEFAULT,
            CwOrderDefaultPolicy.resolve("DEFAULT", Cohort.NEW_INSTALL)
        )
        assertEquals(
            "explicit resume on an existing install",
            ContinueWatchingSortMode.RESUME_FIRST,
            CwOrderDefaultPolicy.resolve("RESUME_FIRST", Cohort.EXISTING_INSTALL)
        )
        assertEquals(
            "explicit streaming style",
            ContinueWatchingSortMode.STREAMING_STYLE,
            CwOrderDefaultPolicy.resolve("STREAMING_STYLE", Cohort.NEW_INSTALL)
        )
    }

    @Test
    fun `an unknown stored value falls back to the cohort default`() {
        assertEquals(
            "unknown value from a newer client",
            ContinueWatchingSortMode.DEFAULT,
            CwOrderDefaultPolicy.resolve("SOMETHING_NEW", Cohort.EXISTING_INSTALL)
        )
    }
}
