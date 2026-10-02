package com.nuvio.tv.core.iptv

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Step 2 — the provider-setup data the apps read: a code's preview, a managed playlist's owner and
 * support contacts, a redeem's result. Parsed defensively (unknown keys ignored, wrong-typed values
 * dropped) because the preview crosses a public web route; it carries names only — never credentials,
 * server addresses or URLs of the playlists themselves.
 */

/**
 * Provider-controlled text (names) as it is shown: Unicode control and format characters (bidi overrides
 * U+202A-202E / U+2066-2069, zero-width, BOM, line and paragraph separators) removed so a name cannot reorder or
 * hide the text around it, whitespace collapsed, trimmed, capped at [MAX_NAME] characters.
 */
object ProviderText {
    const val MAX_NAME = 80
    const val MAX_LIST = 20

    private val STRIPPED = setOf(
        Character.FORMAT, Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.PRIVATE_USE,
    ).map { it.toInt() }.toSet()

    fun clean(raw: String?, max: Int = MAX_NAME): String {
        val sb = StringBuilder()
        var lastSpace = true // no leading space
        for (c in raw.orEmpty()) {
            val whitespace = c.isWhitespace() // tab / newline are controls: they count as a space, not as nothing
            if (whitespace) {
                if (!lastSpace) { sb.append(' '); lastSpace = true }
            } else if (Character.getType(c) !in STRIPPED) {
                sb.append(c); lastSpace = false
            }
        }
        return sb.toString().trim().take(max).trim()
    }
}

/** A support channel a provider chose to show customers. */
data class ProviderContact(val kind: Kind, val text: String, val url: String) {
    enum class Kind { WHATSAPP, TELEGRAM, EMAIL, WEBSITE }
}

/**
 * Provider support contacts, normalized with the web's `normalizeSupport` rules
 * (nuvio-web `src/lib/providers/support.ts`): only values that normalize are kept, so only the contacts
 * the provider set are ever shown. Links are built from the normalized value only, with safe schemes
 * (wa.me / t.me / mailto / https).
 */
data class ProviderSupport(
    val whatsapp: String? = null,
    val telegram: String? = null,
    val email: String? = null,
    val website: String? = null,
) {
    val isEmpty: Boolean get() = whatsapp == null && telegram == null && email == null && website == null

    /** The contacts in the web's order, each with its display text and link. */
    fun contacts(): List<ProviderContact> = buildList {
        // Same order as Mobile / Apple TV.
        telegram?.let { add(ProviderContact(ProviderContact.Kind.TELEGRAM, "@$it", "https://t.me/$it")) }
        whatsapp?.let { add(ProviderContact(ProviderContact.Kind.WHATSAPP, "+$it", "https://wa.me/$it")) }
        email?.let { add(ProviderContact(ProviderContact.Kind.EMAIL, it, "mailto:$it")) }
        website?.let { add(ProviderContact(ProviderContact.Kind.WEBSITE, websiteHost(it), it)) }
    }

    companion object {
        private val EMAIL_RE = Regex("^[A-Za-z0-9.!#$'*+/=_~-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")
        private val TELEGRAM_RE = Regex("^[A-Za-z0-9_]{5,32}$")

        /** Digits only (with country code), 7-15 long: "+44 7700 900123" -> "447700900123". */
        fun normalizeWhatsapp(v: String?): String? {
            val d = v.orEmpty().filter { it in '0'..'9' }
            return d.takeIf { it.length in 7..15 }
        }

        /** A Telegram username, with or without @ or a t.me link. */
        fun normalizeTelegram(v: String?): String? {
            val s = v.orEmpty().trim()
                .replace(Regex("^https?://(www\\.)?t\\.me/", RegexOption.IGNORE_CASE), "")
                .removePrefix("@")
            return s.takeIf { TELEGRAM_RE.matches(it) }
        }

        /** A plain address only: no `? & %`, quotes or brackets that could pre-fill a cc/subject/body. */
        fun normalizeEmail(v: String?): String? {
            val s = v.orEmpty().trim()
            return s.takeIf { it.length <= 254 && EMAIL_RE.matches(it) }
        }

        /**
         * An https address with a registered-looking host (see [com.nuvio.tv.core.links.ExternalLinkPolicy]): no IP
         * literal, no single-label host, no credentials; an IDN host is stored in its punycode form.
         */
        fun normalizeWebsite(v: String?): String? = com.nuvio.tv.core.links.ExternalLinkPolicy.safeHttpsUrl(v.orEmpty())

        private fun websiteHost(url: String): String =
            com.nuvio.tv.core.links.ExternalLinkPolicy.displayHost(url) ?: url.substringAfter("://").substringBefore('/')

        /** Defensive parse of the `support` object a provider RPC / the preview route returns. */
        fun fromJson(element: JsonElement?): ProviderSupport {
            val o = element as? JsonObject ?: return ProviderSupport()
            fun str(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
            return ProviderSupport(
                whatsapp = normalizeWhatsapp(str("whatsapp")),
                telegram = normalizeTelegram(str("telegram")),
                email = normalizeEmail(str("email")),
                website = normalizeWebsite(str("website")),
            )
        }
    }
}

/** One playlist a code will add (name + source kind only). */
data class PreviewPlaylist(val name: String, val sourceType: String)

/** What `GET /api/s/preview` says a code will add. */
data class SetupPreview(
    val providerName: String,
    val support: ProviderSupport,
    val packageName: String?,
    val playlists: List<PreviewPlaylist>,
    /** Add-on NAMES only. Store builds must not show these (decision 6.5). */
    val addons: List<String>,
    val status: String? = null,
    val expiresAt: String? = null,
) {
    companion object {
        fun fromJson(root: JsonElement?): SetupPreview? {
            val o = ((root as? JsonObject)?.get("preview") as? JsonObject) ?: return null
            fun str(el: JsonElement?): String? = (el as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
            val provider = ProviderText.clean(str(o["provider_name"])).takeIf { it.isNotEmpty() } ?: return null
            val playlists = (o["playlists"] as? JsonArray).orEmpty().take(ProviderText.MAX_LIST).mapNotNull { el ->
                val p = el as? JsonObject ?: return@mapNotNull null
                PreviewPlaylist(
                    ProviderText.clean(str(p["name"])).ifEmpty { "Playlist" },
                    ProviderText.clean(str(p["source_type"]), 20),
                )
            }
            val addons = (o["addons"] as? JsonArray).orEmpty().take(ProviderText.MAX_LIST).mapNotNull { el ->
                (str(el) ?: str((el as? JsonObject)?.get("name")))?.let { ProviderText.clean(it) }
            }.filter { it.isNotEmpty() && "://" !in it }
            return SetupPreview(
                providerName = provider,
                support = ProviderSupport.fromJson(o["support"]),
                packageName = ProviderText.clean(str(o["package_name"])).takeIf { it.isNotEmpty() },
                playlists = playlists,
                addons = addons,
                status = str(o["status"]),
                expiresAt = str(o["expires_at"]),
            )
        }
    }
}

/** A playlist the provider installed and still manages, as `get_managed_playlists` reports it. */
data class ManagedPlaylistInfo(
    val playlistKey: String,
    val providerName: String,
    val serviceName: String?,
    val support: ProviderSupport,
    /** The provider's last edit of the service, ISO-8601, or null/absent (unknown: omit "updated <date>"). */
    val serviceUpdatedAt: String? = null,
) {
    companion object {
        /** A profile holds a handful of playlists; this only bounds a hostile or corrupt answer. */
        const val MAX_MANAGED = 200

        fun listFromJson(root: JsonElement?): List<ManagedPlaylistInfo> =
            (root as? JsonArray).orEmpty().take(MAX_MANAGED).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun str(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
                val key = str("playlist_key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val provider = ProviderText.clean(str("provider_name")).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ManagedPlaylistInfo(
                    playlistKey = key,
                    providerName = provider,
                    serviceName = ProviderText.clean(str("service_name")).takeIf { it.isNotEmpty() },
                    support = ProviderSupport.fromJson(o["support"]),
                    serviceUpdatedAt = str("service_updated_at")?.takeIf { it.isNotBlank() },
                )
            }
    }
}

/** `redeem_setup`'s success body. */
data class RedeemResult(
    val status: String,
    val profileIndex: Int?,
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    /** The keys of the playlists it added or updated, in the provider's order. */
    val playlistKeys: List<String>,
    val playlistNames: List<String>,
    /** Why services were skipped (`missing_login`, `invalid_url`), one entry per skipped playlist. */
    val skippedReasons: List<String> = emptyList(),
)

/**
 * What a redeem actually did, pure so the screen holds no logic. A redeem can succeed (the code is spent)
 * and still add NOTHING: the provider has not filled in this customer's login yet, or its server address
 * failed the URL rules. That is not an "added" screen.
 */
object RedeemResultPolicy {
    enum class Kind { ADDED, ALREADY_SET_UP, NOTHING_NO_LOGIN, NOTHING_BAD_ADDRESS, NOTHING }

    fun classify(r: RedeemResult): Kind = when {
        r.added + r.updated > 0 -> Kind.ADDED
        r.status == "already_redeemed" -> Kind.ALREADY_SET_UP
        // `unchanged` = the playlist was already linked to this setup (a second code of the same package for this
        // account): it is in the account. Same as Mobile: that reads as added (and opens it), not as "nothing".
        r.unchanged > 0 -> Kind.ADDED
        r.skippedReasons.any { it != "invalid_url" } -> Kind.NOTHING_NO_LOGIN
        r.skippedReasons.isNotEmpty() -> Kind.NOTHING_BAD_ADDRESS
        else -> Kind.NOTHING
    }
}
