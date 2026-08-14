package com.nuvio.tv.core.iptv.stalker

/**
 * The STB identities we are willing to present to a portal, and the order we try them in.
 *
 * Portals fingerprint the set-top box. Plenty accept anything; a meaningful minority only serve a
 * device whose `stb_type` / `image_version` / `hw_version` / User-Agent look like the box they were
 * provisioned for, and answer the plain text "Authorization failed." to everything else. Sending a
 * single hardcoded identity turns those portals into a support ticket that reads "works in TiviMate,
 * not in Tuvora" — there is nothing wrong with the user's MAC.
 *
 * [LADDER] starts with the identity we have always sent, so a portal that already works keeps its
 * existing first-try behaviour and costs no extra requests. Only a rejection moves down the list.
 */
internal data class StalkerMagPreset(
    val id: String,
    val stbType: String,
    val imageVersion: String,
    val hwVersion: String,
    /** The `ver` parameter: the box's firmware/portal/API version blob. */
    val stbVer: String,
    val userAgent: String,
    val xUserAgent: String,
)

internal object StalkerMagPresets {

    /** Exactly what Tuvora sent before the ladder existed. Must stay first. */
    val GENERIC_MAG250 = StalkerMagPreset(
        id = "generic_mag250",
        stbType = "MAG250",
        imageVersion = "218",
        hwVersion = "1.7-BD-00",
        stbVer = "ImageDescription: 0.2.18-r14-pub-250; ImageDate: Wed Aug 29 10:49:52 EEST 2018; " +
            "PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; " +
            "Player Engine version: 0x58c",
        userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
            "MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
        xUserAgent = "Model: MAG250; Link: WiFi",
    )

    /** Portals provisioned for a MAG254 that reject the 250's image/hw pair. */
    val MAG254_STRICT = StalkerMagPreset(
        id = "mag254_strict",
        stbType = "MAG254",
        imageVersion = "254",
        hwVersion = "2.6-IB-00",
        stbVer = "ImageDescription: 0.2.18-r23-254; ImageDate: Thu Nov 1 11:14:12 EET 2018; " +
            "PORTAL version: 5.6.8; API Version: JS API version: 343; STB API version: 146; " +
            "Player Engine version: 0x58c",
        userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
            "MAG254 stbapp ver: 4 rev: 2721 Safari/533.3",
        xUserAgent = "Model: MAG254; Link: WiFi",
    )

    /** Newer Ministra deployments that expect a MAG322-era box. */
    val MINISTRA_MAG322 = StalkerMagPreset(
        id = "ministra_mag322",
        stbType = "MAG322",
        imageVersion = "221",
        hwVersion = "2.6-IB-00",
        stbVer = "ImageDescription: 0.2.21-r14-254; ImageDate: Wed Apr 24 13:42:11 EEST 2019; " +
            "PORTAL version: 5.6.8; API Version: JS API version: 343; STB API version: 146; " +
            "Player Engine version: 0x5a1",
        userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
            "MAG322 stbapp ver: 4 rev: 2721 Safari/533.3",
        xUserAgent = "Model: MAG322; Link: WiFi",
    )

    val LADDER: List<StalkerMagPreset> = listOf(GENERIC_MAG250, MAG254_STRICT, MINISTRA_MAG322)

    val DEFAULT: StalkerMagPreset get() = LADDER.first()

    fun byId(id: String?): StalkerMagPreset? = LADDER.firstOrNull { it.id == id }

    /**
     * The identity to try after [current] was rejected, or null when the ladder is exhausted and the
     * rejection is genuinely the account's problem rather than the identity's.
     *
     * A [current] we do not recognise restarts the ladder rather than ending it: a preset id can
     * outlive a release (it is remembered per account), and forgetting one must not strand the
     * account on a single failed attempt.
     */
    fun next(current: StalkerMagPreset?): StalkerMagPreset? {
        if (current == null) return DEFAULT
        val index = LADDER.indexOfFirst { it.id == current.id }
        if (index < 0) return DEFAULT
        return LADDER.getOrNull(index + 1)
    }
}
