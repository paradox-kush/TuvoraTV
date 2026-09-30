package com.nuvio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonSyncMergeTest {

    private val torrentio = "https://torrentio.test/config"
    private val cinemeta = "https://cinemeta.test"
    private val subs = "https://subs.test"

    @Test
    fun addonsAddedOnADeviceThatNeverSyncedSurviveAnEmptyServerAndArePushed() {
        // The field report: addons installed on one device never reached the server, so the
        // pull saw an empty server list and replaced the device's addons with nothing.
        val outcome = AddonSyncMerge.merge(
            local = listOf(torrentio, cinemeta),
            remote = emptyList(),
            lastSynced = null,
        )

        assertEquals(listOf(torrentio, cinemeta), outcome.urls)
        assertTrue(outcome.pushNeeded)
    }

    @Test
    fun aNeverSyncedDeviceKeepsTheAccountAddonsAndAppendsItsOwn() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(subs, torrentio),
            remote = listOf(cinemeta, torrentio),
            lastSynced = null,
        )

        assertEquals(listOf(cinemeta, torrentio, subs), outcome.urls)
        assertTrue(outcome.pushNeeded)
    }

    @Test
    fun untouchedFactoryDefaultsDoNotResurrectAddonsTheAccountRemoved() {
        // A fresh TV starts with the default addons; using them as the baseline means a user who
        // removed OpenSubtitles on their phone doesn't get it pushed back by signing in here.
        val defaults = listOf(cinemeta, subs)
        val outcome = AddonSyncMerge.merge(
            local = defaults,
            remote = listOf(cinemeta, torrentio),
            lastSynced = defaults,
        )

        assertEquals(listOf(cinemeta, torrentio), outcome.urls)
        assertFalse(outcome.pushNeeded)
    }

    @Test
    fun withNoLocalEditsTheServerListWinsEvenWhenEmpty() {
        // Deleting every addon on another device must stick here too.
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta, torrentio),
            remote = emptyList(),
            lastSynced = listOf(cinemeta, torrentio),
        )

        assertEquals(emptyList<String>(), outcome.urls)
        assertFalse(outcome.pushNeeded)
    }

    @Test
    fun withNoLocalEditsServerAdditionsAndOrderAreTakenAsIs() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta, torrentio),
            remote = listOf(torrentio, subs, cinemeta),
            lastSynced = listOf(cinemeta, torrentio),
        )

        assertEquals(listOf(torrentio, subs, cinemeta), outcome.urls)
        assertFalse(outcome.pushNeeded)
    }

    @Test
    fun anAddonAddedLocallySinceTheLastSyncIsAppendedAndPushed() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta, subs),
            remote = listOf(cinemeta, torrentio),
            lastSynced = listOf(cinemeta),
        )

        assertEquals(listOf(cinemeta, torrentio, subs), outcome.urls)
        assertTrue(outcome.pushNeeded)
    }

    @Test
    fun anAddonRemovedLocallySinceTheLastSyncIsDroppedFromTheServerListAndPushed() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta),
            remote = listOf(cinemeta, torrentio),
            lastSynced = listOf(cinemeta, torrentio),
        )

        assertEquals(listOf(cinemeta), outcome.urls)
        assertTrue(outcome.pushNeeded)
    }

    @Test
    fun anAddonAnotherDeviceRemovedIsNotResurrectedFromTheLastSyncedList() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(cinemeta, torrentio),
            remote = listOf(cinemeta),
            lastSynced = listOf(cinemeta, torrentio),
        )

        assertEquals(listOf(cinemeta), outcome.urls)
        assertFalse(outcome.pushNeeded)
    }

    @Test
    fun urlsAreComparedByKeySoTheSameAddonIsNeverDuplicated() {
        val outcome = AddonSyncMerge.merge(
            local = listOf("HTTPS://TORRENTIO.TEST/config"),
            remote = listOf(torrentio),
            lastSynced = null,
            key = { it.lowercase() },
        )

        assertEquals(listOf(torrentio), outcome.urls)
        assertFalse(outcome.pushNeeded)
    }

    @Test
    fun duplicateLocalEntriesAreCollapsed() {
        val outcome = AddonSyncMerge.merge(
            local = listOf(subs, subs),
            remote = emptyList(),
            lastSynced = null,
        )

        assertEquals(listOf(subs), outcome.urls)
        assertTrue(outcome.pushNeeded)
    }
}
