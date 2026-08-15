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
     * A `status: 1` profile refusal. [deviceConflict] = the portal's own message names the DEVICE
     * BINDING (another device's identity is pinned to this MAC) — the one refusal with a user
     * remedy. [portalText] is the markup-stripped `msg`/`block_msg` for error surfaces.
     */
    data class Refusal(val deviceConflict: Boolean, val portalText: String?)

    /**
     * `get_profile` status decode: 0/absent = OK, 2 = wants `do_auth` (see [stepsAfterProfile]),
     * 1 = REFUSED — the line is disabled, the MAC unknown, or another device owns the binding.
     * A bare `{status: 1}` with no message is still a refusal, never a success.
     *
     * The device-conflict split matches a NARROW phrase set against the portal's own STRUCTURED
     * `msg`/`block_msg` (never a raw HTML body). Kept to the binding itself: "device" alone
     * appears in unrelated refusals ("device limit reached", "no device selected"), and
     * mislabelling one of those would hand the user a remedy that cannot work.
     */
    fun refusalAfterProfile(status: Int?, msg: String?, blockMsg: String?): Refusal? {
        if (status != 1) return null
        val text = listOfNotNull(msg, blockMsg)
            .map { stripMarkup(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" — ")
            .ifEmpty { null }
        val conflict = text != null && DEVICE_CONFLICT_PATTERNS.any { it.containsMatchIn(text) }
        return Refusal(deviceConflict = conflict, portalText = text)
    }

    /** `block_msg` routinely carries markup ("Your STB is damaged.<br/>Call the provider."). */
    private fun stripMarkup(text: String): String =
        text.replace(MARKUP, " ").replace(SPACES, " ").trim()

    private val MARKUP = Regex("<[^>]*>")
    private val SPACES = Regex("\\s+")

    /** Device-conflict phrasings seen in the wild (iptvnator ships the same set). */
    private val DEVICE_CONFLICT_PATTERNS = listOf(
        Regex("""device\s*conflict""", RegexOption.IGNORE_CASE),
        Regex("""device[\s_-]?id[^.!?]{0,40}?(mismatch|conflict|does\s*not\s*match|not\s*match)""", RegexOption.IGNORE_CASE),
    )

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
