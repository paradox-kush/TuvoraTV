package com.nuvio.tv.core.announcements

import com.nuvio.tv.domain.model.Announcement

/**
 * Pure decisions for in-app announcements (house pattern: RadarLiveRefreshPolicy).
 *
 * The fetch is triggered by Home becoming visible, never by a timer; [shouldFetch] caps that to one
 * request per [FETCH_INTERVAL_MS] so repeated resumes cost nothing.
 */
object AnnouncementPolicy {
    const val FETCH_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L

    private const val HTTPS_PREFIX = "https://"

    /**
     * True when never fetched, or at least [FETCH_INTERVAL_MS] has passed. A clock that moved
     * backwards (last fetch "in the future") also fetches, so a wrong clock can't block forever.
     */
    fun shouldFetch(lastFetchedAtMs: Long?, nowMs: Long): Boolean {
        if (lastFetchedAtMs == null) return true
        if (nowMs < lastFetchedAtMs) return true
        return nowMs - lastFetchedAtMs >= FETCH_INTERVAL_MS
    }

    /** First announcement in server order that isn't dismissed and has a displayable title. */
    fun pick(items: List<Announcement>, dismissedIds: Set<String>): Announcement? =
        items.firstOrNull { it.id !in dismissedIds && it.title.isNotBlank() }

    /** The url only when it is an https link with a host part; anything else is dropped. */
    fun safeCtaUrl(url: String?): String? {
        val trimmed = url?.trim() ?: return null
        if (!trimmed.startsWith(HTTPS_PREFIX)) return null
        if (trimmed.length <= HTTPS_PREFIX.length) return null
        return trimmed
    }

    data class Cta(val label: String, val url: String)

    /** The CTA to show, or null (hidden) when either the label or a safe url is missing. */
    fun cta(announcement: Announcement): Cta? {
        val label = announcement.ctaLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = safeCtaUrl(announcement.ctaUrl) ?: return null
        return Cta(label, url)
    }
}
