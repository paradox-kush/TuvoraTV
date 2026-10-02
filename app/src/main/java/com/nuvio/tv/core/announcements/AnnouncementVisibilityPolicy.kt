package com.nuvio.tv.core.announcements

import com.nuvio.tv.domain.model.Announcement
import com.nuvio.tv.domain.model.AuthState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Who is looking at Home, as far as announcement visibility cares. */
sealed interface AnnouncementSignIn {
    /** Auth has not settled yet: a policy notice waits rather than flashing up and vanishing. */
    data object Resolving : AnnouncementSignIn
    data object NoAccount : AnnouncementSignIn
    data class Account(val createdAtMs: Long?, val acceptedTermsVersion: String?) : AnnouncementSignIn
}

/**
 * [installFirstSeenAtMs] is when this install first ran (Android: PackageInfo.firstInstallTime);
 * 0 when unknown, which counts as an existing install.
 */
data class AnnouncementViewer(val signIn: AnnouncementSignIn, val installFirstSeenAtMs: Long)

/** The facts the policy reads off the session user. */
data class AnnouncementAccountRecord(val userId: String, val createdAtMs: Long?, val acceptedTermsVersion: String?)

/**
 * UX84 (TV twin of Mobile/Desktop `AnnouncementVisibilityPolicy`): a kind=policy announcement
 * ("we've updated our Privacy Policy") is news only to people who saw the old policy.
 *  - hidden for an account created at/after the notice's starts_at, or whose accepted terms
 *    version is dated on/after that day — they signed up under the current terms;
 *  - hidden for a signed-out install first opened at/after starts_at — it never ran under the old terms;
 *  - shown to everyone else until dismissed. Unknown facts never hide it.
 * Other kinds are untouched.
 */
object AnnouncementVisibilityPolicy {
    const val KIND_POLICY = "policy"
    private const val TERMS_VERSION_KEY = "terms_version"

    fun isVisible(announcement: Announcement, viewer: AnnouncementViewer): Boolean {
        if (!announcement.kind.equals(KIND_POLICY, ignoreCase = true)) return true
        val startsAtMs = parseStartsAtMs(announcement.startsAt) ?: return true
        return when (val signIn = viewer.signIn) {
            AnnouncementSignIn.Resolving -> false
            AnnouncementSignIn.NoAccount ->
                viewer.installFirstSeenAtMs <= 0L || viewer.installFirstSeenAtMs < startsAtMs
            is AnnouncementSignIn.Account -> {
                val createdUnderCurrentTerms = signIn.createdAtMs?.let { it >= startsAtMs } == true
                val acceptedCurrentTerms = parseTermsDate(signIn.acceptedTermsVersion)
                    ?.let { !it.isBefore(utcDate(startsAtMs)) } == true
                !createdUnderCurrentTerms && !acceptedCurrentTerms
            }
        }
    }

    /** First announcement in server order that isn't dismissed, has a title, and is for this viewer. */
    fun pick(items: List<Announcement>, dismissedIds: Set<String>, viewer: AnnouncementViewer): Announcement? =
        items.firstOrNull { it.id !in dismissedIds && it.title.isNotBlank() && isVisible(it, viewer) }

    /** [account] only counts when it is the signed-in account's own record. */
    fun signInFrom(state: AuthState, account: AnnouncementAccountRecord?): AnnouncementSignIn = when (state) {
        AuthState.Loading -> AnnouncementSignIn.Resolving
        AuthState.SignedOut -> AnnouncementSignIn.NoAccount
        is AuthState.FullAccount -> {
            val own = account?.takeIf { it.userId == state.userId }
            AnnouncementSignIn.Account(own?.createdAtMs, own?.acceptedTermsVersion)
        }
    }

    /** The `terms_version` the account accepted at sign-up (stored in its user metadata). */
    fun termsVersionFrom(userMetadata: JsonObject?): String? {
        val value = userMetadata?.get(TERMS_VERSION_KEY) as? JsonPrimitive ?: return null
        return value.takeIf { it.isString }?.content
    }

    private fun parseStartsAtMs(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun parseTermsDate(raw: String?): LocalDate? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDate.parse(text) }.getOrNull()
    }

    private fun utcDate(epochMs: Long): LocalDate =
        java.time.Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).toLocalDate()
}
