package com.nuvio.tv.core.iptv.stalker

/**
 * What else a portal needs after `get_profile` before it will serve anything.
 *
 * Handshake plus profile is enough for most portals, but two families answer the profile call
 * happily and then refuse every content call until one more step is taken. Both look identical from
 * the outside — an account that browses to an empty app — so the profile response is the only place
 * to tell them apart:
 *
 *  - **Module-gated (Ministra).** The profile carries `auth_access: false`, meaning the portal has
 *    not yet decided what this box may see. It expects `get_modules` and stays shut until it comes.
 *  - **Explicit authorization.** The profile reports a non-zero `status`: the line is provisioned
 *    but not authorised for this session, and the portal wants `do_auth` with the account's own
 *    login and password — the ones the user already typed into the playlist.
 *
 * Kept pure so the decision can be tested against profile payloads rather than portals.
 */
internal object StalkerBootstrapPolicy {

    enum class Step {
        /** `do_auth` with the playlist's stored login/password. */
        DO_AUTH,

        /** `get_modules`, which is what unlocks a module-gated portal. */
        GET_MODULES,
    }

    /**
     * Steps to run, in order, after a successful `get_profile`.
     *
     * [authAccess] and [status] are null when the portal omits them, which most do — absence means
     * "nothing further needed" rather than "assume the worst", because sending an unwanted `do_auth`
     * to a healthy portal is a wasted call at best and a rejection at worst.
     *
     * Authorization comes before modules: a portal asking for both wants to know who this is before
     * it will say what they may watch.
     */
    fun stepsAfterProfile(
        authAccess: Boolean?,
        status: Int?,
        hasCredentials: Boolean,
    ): List<Step> = buildList {
        // No credentials means no do_auth to send. The portal's own error then stands, which is
        // more useful to the user than a call we know is incomplete.
        if (status != null && status != 0 && hasCredentials) add(Step.DO_AUTH)
        if (authAccess == false) add(Step.GET_MODULES)
    }
}
