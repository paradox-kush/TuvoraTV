package com.nuvio.tv.core.profile

interface ProfileScopedCredentialStore {
    fun removeProfile(profileId: Int)
    fun clearAllProfiles()

    /**
     * Exchange two profiles' stored credentials, for "make this my main profile". Credentials are
     * keyed by profile id, so without this a promotion would hand each profile the other's linked
     * account.
     */
    fun swapProfiles(a: Int, b: Int)
}
