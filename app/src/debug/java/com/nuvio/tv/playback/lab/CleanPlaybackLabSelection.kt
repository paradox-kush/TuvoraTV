package com.nuvio.tv.playback.lab

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.SecretValue
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.wiring.NavigationPlaybackInput
import com.nuvio.tv.playback.wiring.PlaybackRequestMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal enum class LabEligibilityReason {
    ELIGIBLE,
    SYSTEM_DNS_FALLBACK,
    ACCOUNT_DISABLED,
    SOURCE_UNSUPPORTED,
    PREFLIGHT_FAILED,
    PREFERENCE_REJECTED,
    REQUIREMENTS_REJECTED,
    NO_ELIGIBLE_GRAPH,
}

/** One saved live recent. Secret-bearing values are opaque and its string form is display-safe. */
internal class SelectedDebugFixture(
    val fingerprint: String,
    val displayLabel: String,
    val accountEnabled: Boolean,
    val sourceType: String,
    private val contentId: SecretValue,
    private val streamUrl: SecretValue,
    private val dnsProvider: SecretValue,
) {
    fun usesDnsProvider(block: (String) -> Boolean): Boolean = block(dnsProvider.value)

    fun <T> mapDnsProvider(block: (String) -> T): T = block(dnsProvider.value)

    fun playbackIntent(dnsPolicy: DnsPolicy): LabPlaybackIntent {
        val mapped = PlaybackRequestMapper().map(
            NavigationPlaybackInput(
                url = streamUrl.value,
                contentType = ContentType.LIVE,
                contentKey = contentId,
                providerConnectionLimit = 1,
                dnsPolicy = dnsPolicy,
            ),
        )
        return LabPlaybackIntent(mapped.request, mapped.evidence, SessionProfile.GUIDE)
    }

    override fun toString(): String =
        "SelectedDebugFixture(fingerprint=$fingerprint, label=$displayLabel)"
}

internal class LabPlaybackIntent(
    val request: PlaybackRequest,
    val evidence: StreamEvidence,
    val profile: SessionProfile,
) {
    /** Both adapters receive this same object; engine choice is graph policy, not request intent. */
    fun requestFor(engine: EngineType): PlaybackRequest {
        require(engine == EngineType.MEDIA3 || engine == EngineType.LIBMPV)
        return request
    }

    override fun toString(): String =
        "LabPlaybackIntent(profile=$profile, request=${request.summary()})"
}

internal data class DebugFixtureOption(
    val ordinal: Int,
    val playlistOrdinal: Int,
    val fingerprint: String,
    val displayLabel: String,
    val fixture: SelectedDebugFixture,
)

internal sealed interface DebugFixtureCatalog {
    data class Ready(val options: List<DebugFixtureOption>) : DebugFixtureCatalog {
        init {
            require(options.isNotEmpty())
        }

        override fun toString(): String = "DebugFixtureCatalog.Ready(count=${options.size})"
    }

    data class Blocked(val code: LabReadinessCode) : DebugFixtureCatalog
}

/** Deterministic active-profile catalog. No provider/account identity survives into display data. */
internal fun buildDebugFixtureCatalog(
    accounts: List<XtreamAccount>,
    recents: List<LiveChannelRef>,
): DebugFixtureCatalog {
    val uniqueAccounts = accounts.distinctBy(XtreamAccount::id)
    val accountById = uniqueAccounts.associateBy(XtreamAccount::id)
    val playlistOrdinals = uniqueAccounts
        .sortedWith(compareBy<XtreamAccount>({ stableFingerprint("account", it.id) }, { it.id }))
        .mapIndexed { index, account -> account.id to index + 1 }
        .toMap()

    val candidates = recents.mapNotNull { recent ->
        val parsed = XtreamItemRegistry.parseId(recent.id) ?: return@mapNotNull null
        if (parsed.kind != "live" || recent.streamUrl.isBlank()) return@mapNotNull null
        val account = accountById[parsed.accountId] ?: return@mapNotNull null
        val fingerprint = stableFingerprint("recent", account.id, recent.id)
        CatalogCandidate(
            playedAt = recent.playedAt ?: Long.MIN_VALUE,
            fingerprint = fingerprint,
            playlistOrdinal = playlistOrdinals.getValue(account.id),
            safeChannelLabel = safeLiveLabel(recent.name),
            account = account,
            recent = recent,
        )
    }.sortedWith(
        compareByDescending<CatalogCandidate> { it.playedAt }
            .thenBy(CatalogCandidate::fingerprint),
    ).distinctBy(CatalogCandidate::fingerprint)

    if (candidates.isEmpty()) {
        return DebugFixtureCatalog.Blocked(LabReadinessCode.NO_RECENT_LIVE_CHANNEL)
    }
    return DebugFixtureCatalog.Ready(
        candidates.mapIndexed { index, candidate ->
            val ordinal = index + 1
            val display = String.format(
                Locale.ROOT,
                "%02d · P%02d · %s · #%s",
                ordinal,
                candidate.playlistOrdinal,
                candidate.safeChannelLabel,
                candidate.fingerprint,
            )
            val fixture = SelectedDebugFixture(
                fingerprint = candidate.fingerprint,
                displayLabel = display,
                accountEnabled = candidate.account.enabled,
                sourceType = candidate.account.sourceType,
                contentId = SecretValue(candidate.recent.id),
                streamUrl = SecretValue(candidate.recent.streamUrl),
                dnsProvider = SecretValue(candidate.account.dnsProvider),
            )
            DebugFixtureOption(
                ordinal = ordinal,
                playlistOrdinal = candidate.playlistOrdinal,
                fingerprint = candidate.fingerprint,
                displayLabel = display,
                fixture = fixture,
            )
        },
    )
}

/** Fail closed on labels that resemble transport, authentication, or query material. */
internal fun safeLiveLabel(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || SECRETISH_LABEL.containsMatchIn(trimmed)) return DEFAULT_LIVE_LABEL
    val sanitized = buildString {
        trimmed.forEach { character ->
            append(
                when {
                    character.isLetterOrDigit() -> character
                    character.isWhitespace() -> ' '
                    character in SAFE_LABEL_PUNCTUATION -> character
                    else -> ' '
                },
            )
        }
    }.replace(WHITESPACE, " ").trim().take(MAX_LABEL_LENGTH).trim()
    return sanitized.takeIf(String::isNotBlank) ?: DEFAULT_LIVE_LABEL
}

internal fun staticEligibility(fixture: SelectedDebugFixture, engine: EngineType): LabEligibilityReason? = when {
    !fixture.accountEnabled -> LabEligibilityReason.ACCOUNT_DISABLED
    fixture.sourceType == XtreamAccount.SOURCE_STALKER -> LabEligibilityReason.SOURCE_UNSUPPORTED
    engine !in setOf(EngineType.MEDIA3, EngineType.LIBMPV) -> LabEligibilityReason.NO_ELIGIBLE_GRAPH
    else -> null
}

private data class CatalogCandidate(
    val playedAt: Long,
    val fingerprint: String,
    val playlistOrdinal: Int,
    val safeChannelLabel: String,
    val account: XtreamAccount,
    val recent: LiveChannelRef,
)

private fun stableFingerprint(vararg values: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        digest.update(value.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
    }
    return digest.digest().take(FINGERPRINT_BYTES).joinToString("") { byte ->
        String.format(Locale.ROOT, "%02X", byte)
    }
}

private const val DEFAULT_LIVE_LABEL = "Live channel"
private const val MAX_LABEL_LENGTH = 36
private const val FINGERPRINT_BYTES = 5
private val SAFE_LABEL_PUNCTUATION = setOf('-', '_', '.', '(', ')', '[', ']')
private val WHITESPACE = Regex("\\s+")
private val SECRETISH_LABEL = Regex(
    """(?i)([a-z][a-z0-9+.-]*://|[@?&=]|\\b(user(name)?|pass(word)?|token|auth(orization)?|cookie|header|secret|api[-_ ]?key)\\b)""",
)
