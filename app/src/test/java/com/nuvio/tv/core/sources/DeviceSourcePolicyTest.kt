package com.nuvio.tv.core.sources

import org.junit.Test
import org.junit.Assert.assertEquals

class DeviceSourcePolicyTest {
    private val ready = DeviceSourceInputs(true, true, true, true)

    @Test fun accountAndBuildRestrictionsCannotBeOverriddenByLocalOn() {
        assertEquals(DeviceSourceAvailability.ACCOUNT_DISABLED,
            DeviceSourcePolicy.availability(ready.copy(accountEnabled = false, pendingChoice = true, hasPendingChoice = true)))
        assertEquals(DeviceSourceAvailability.UNSUPPORTED_BUILD,
            DeviceSourcePolicy.availability(ready.copy(buildSupported = false, acknowledgedChoice = true)))
    }

    @Test fun freshInstallationCannotStartSourcesBeforeSetupSelection() {
        assertEquals(DeviceSourceAvailability.SETUP_REQUIRED,
            DeviceSourcePolicy.availability(ready.copy(setupResolved = false)))
    }

    @Test fun offlineOffSurvivesNewCloudSnapshotAndConflict() {
        assertEquals(DeviceSourceAvailability.DEVICE_DISABLED, DeviceSourcePolicy.availability(
            ready.copy(acknowledgedChoice = true, pendingChoice = false, hasPendingChoice = true, pendingConflict = true)))
    }

    @Test fun staleEnableAndResetCannotSilentlyUndoSavedOff() {
        for (pending in listOf(true, null)) {
            assertEquals(DeviceSourceAvailability.DEVICE_DISABLED, DeviceSourcePolicy.availability(
                ready.copy(acknowledgedChoice = false, pendingChoice = pending, hasPendingChoice = true, pendingConflict = true)))
        }
    }

    @Test fun resetInheritsButStillHonorsAccountDisable() {
        assertEquals(DeviceSourceAvailability.AVAILABLE, DeviceSourcePolicy.availability(
            ready.copy(acknowledgedChoice = false, hasPendingChoice = true)))
        assertEquals(DeviceSourceAvailability.ACCOUNT_DISABLED, DeviceSourcePolicy.availability(
            ready.copy(accountEnabled = false, hasPendingChoice = true)))
    }

    @Test fun restoreDoesNotImplyRestoringCredentialsOrRemovedSources() {
        assertEquals(DeviceSourceAvailability.SIGN_IN_REQUIRED,
            DeviceSourcePolicy.availability(ready.copy(locallyAuthenticated = false)))
        assertEquals(DeviceSourceAvailability.SOURCE_REMOVED,
            DeviceSourcePolicy.availability(ready.copy(sourceExists = false, acknowledgedChoice = true)))
    }

    @Test fun unchangedHintsAndInvisibleScreensMakeNoRequest() {
        assertEquals(DevicePreferenceRefresh.NONE, DevicePreferenceRefreshPolicy.decide(true,true,true,4,4))
        assertEquals(DevicePreferenceRefresh.NONE, DevicePreferenceRefreshPolicy.decide(true,true,true,4,3))
        assertEquals(DevicePreferenceRefresh.NONE, DevicePreferenceRefreshPolicy.decide(false,true,true,4,9))
        assertEquals(DevicePreferenceRefresh.NONE, DevicePreferenceRefreshPolicy.decide(true,false,true,null,9))
        assertEquals(DevicePreferenceRefresh.NONE, DevicePreferenceRefreshPolicy.decide(true,true,false,null,9))
        assertEquals(DevicePreferenceRefresh.DELTA, DevicePreferenceRefreshPolicy.decide(true,true,true,4,9))
        assertEquals(DevicePreferenceRefresh.DELTA, DevicePreferenceRefreshPolicy.decide(true,true,true,4,null,true))
        assertEquals(DevicePreferenceRefresh.SNAPSHOT, DevicePreferenceRefreshPolicy.decide(true,true,true,null,9))
    }

    @Test fun explicitInstallationOnlyPermitsItsOwnManifest() {
        val request = AddonInstallationRequest("https://addon.example/config?token=A")
        org.junit.Assert.assertTrue(request.permitsManifest("https://addon.example/config/manifest.json?token=A"))
        org.junit.Assert.assertFalse(request.permitsManifest("https://addon.example/config/catalog/movie/top.json?token=A"))
        org.junit.Assert.assertFalse(request.permitsManifest("https://addon.example/config/stream/movie/id.json?token=A"))
        org.junit.Assert.assertFalse(request.permitsManifest("https://addon.example/config/manifest.json?token=B"))
        org.junit.Assert.assertFalse(request.permitsManifest("https://addon.example/other/manifest.json?token=A"))
    }
}
