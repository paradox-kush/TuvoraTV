package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.core.recommendations.LauncherItem
import com.nuvio.tv.core.recommendations.LauncherRemovalPolicy
import com.nuvio.tv.core.recommendations.WatchNextTypePolicy
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.ui.screens.home.ResumeRankPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JUnit argument order: assertEquals(message, expected, actual). */
class LauncherItemsPlannerTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun settings(
        sortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT,
        includeNextUp: Boolean = true,
        dismissed: Set<String> = emptySet()
    ) = LauncherPlanSettings(
        daysCap = 0, // all
        dismissedNextUp = dismissed,
        useEpisodeThumbnails = true,
        sortMode = sortMode,
        includeNextUp = includeNextUp
    )

    private fun resume(
        id: String,
        ageMs: Long,
        season: Int? = null,
        episode: Int? = null,
        position: Long = 600_000L,
        duration: Long = 3_600_000L,
        percent: Float? = null
    ) = CachedInProgressItem(
        contentId = id,
        contentType = if (season == null) "movie" else "series",
        name = id,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = id,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = now - ageMs,
        progressPercent = percent
    )

    private fun next(
        id: String,
        lastWatchedAgeMs: Long,
        season: Int = 1,
        episode: Int = 2,
        hasAired: Boolean = true,
        releaseTimestamp: Long? = null,
        isReleaseAlert: Boolean = false
    ) = CachedNextUpItem(
        contentId = id,
        contentType = "series",
        name = id,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$id:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        thumbnail = null,
        hasAired = hasAired,
        lastWatched = now - lastWatchedAgeMs,
        sortTimestamp = if (isReleaseAlert && releaseTimestamp != null) releaseTimestamp else now - lastWatchedAgeMs,
        releaseTimestamp = releaseTimestamp,
        isReleaseAlert = isReleaseAlert
    )

    private fun plan(
        inProgress: List<CachedInProgressItem> = emptyList(),
        nextUp: List<CachedNextUpItem> = emptyList(),
        settings: LauncherPlanSettings = settings(),
        removed: Map<String, Long> = emptyMap(),
        at: Long = now
    ) = LauncherItemsPlanner.plan(inProgress, nextUp, settings, at, removed)

    private fun ids(items: List<LauncherItem>) = items.map { it.progress.contentId }

    // --- aired state ---

    @Test
    fun `an episode that aired while the app was closed shows even though the cache says not aired`() {
        val aired = next("show", day, hasAired = false, releaseTimestamp = now - hour)
        assertEquals("shown", listOf("show"), ids(plan(nextUp = listOf(aired))))
    }

    @Test
    fun `an episode that has not aired yet stays out`() {
        val future = next("show", day, hasAired = false, releaseTimestamp = now + day)
        assertEquals("hidden", emptyList<String>(), ids(plan(nextUp = listOf(future))))
        val unknown = next("other", day, hasAired = false, releaseTimestamp = null)
        assertEquals("unknown date trusts the cache", emptyList<String>(), ids(plan(nextUp = listOf(unknown))))
    }

    @Test
    fun `an episode that aired while the app was closed is published as NEW`() {
        val aired = next("show", 10 * day, hasAired = false, releaseTimestamp = now - hour)
        assertEquals("new", Kind.NewEpisode, plan(nextUp = listOf(aired)).single().kind)
    }

    @Test
    fun `kinds map to resume, next and new`() {
        val items = plan(
            inProgress = listOf(resume("film", day)),
            nextUp = listOf(
                next("plain", 2 * day),
                next("alert", 3 * day, releaseTimestamp = now - hour, isReleaseAlert = true)
            )
        )
        val kinds = items.associate { it.progress.contentId to it.kind }
        assertEquals(
            "kinds",
            mapOf("film" to Kind.Resume, "plain" to Kind.NextUp, "alert" to Kind.NewEpisode),
            kinds
        )
    }

    @Test
    fun `published types follow the policy`() {
        val items = plan(
            inProgress = listOf(resume("film", day), resume("percent-only", 2 * day, position = 0, duration = 0, percent = 40f)),
            nextUp = listOf(next("plain", 3 * day), next("alert", 4 * day, releaseTimestamp = now - hour, isReleaseAlert = true))
        )
        val types = items.associate {
            val p = it.progress
            val position = WatchNextTypePolicy.resolvePositionMs(p.position, p.duration, p.progressPercent)
            p.contentId to WatchNextTypePolicy.typeFor(it.kind, position, p.duration)
        }
        assertEquals(
            "types",
            mapOf(
                "film" to WatchNextTypePolicy.WatchNextType.CONTINUE,
                "percent-only" to WatchNextTypePolicy.WatchNextType.NEXT,
                "plain" to WatchNextTypePolicy.WatchNextType.NEXT,
                "alert" to WatchNextTypePolicy.WatchNextType.NEW
            ),
            types
        )
    }

    // --- removal ---

    @Test
    fun `a removed item is absent after reconcile`() {
        val removed = LauncherRemovalPolicy.recordRemoval(emptyMap(), "wn_film", now - hour)
        val items = plan(inProgress = listOf(resume("film", day), resume("other", 2 * day)), removed = removed)
        assertEquals("removed item gone", listOf("other"), ids(items))
    }

    @Test
    fun `a removed item stays absent on every later periodic run`() {
        val removed = LauncherRemovalPolicy.recordRemoval(emptyMap(), "wn_film", now - hour)
        val cache = listOf(resume("film", day))
        repeat(3) { run ->
            val items = plan(inProgress = cache, removed = removed, at = now + run * 15 * 60_000L)
            assertEquals("run $run", emptyList<String>(), ids(items))
        }
    }

    @Test
    fun `a removed preview-channel item is absent from both surfaces`() {
        val removed = LauncherRemovalPolicy.recordRemoval(emptyMap(), "show_s1e2", now - hour)
        val items = plan(inProgress = listOf(resume("show", day, season = 1, episode = 2)), removed = removed)
        assertEquals("gone", emptyList<String>(), ids(items))
    }

    @Test
    fun `a later episode of a removed show may publish`() {
        val removed = LauncherRemovalPolicy.recordRemoval(emptyMap(), "wn_show_s1e2", now - hour)
        val items = plan(inProgress = listOf(resume("show", 2 * hour, season = 1, episode = 3)), removed = removed)
        assertEquals("next episode is a different item", listOf("show"), ids(items))
    }

    @Test
    fun `a replay after removal publishes again`() {
        val removed = LauncherRemovalPolicy.recordRemoval(emptyMap(), "wn_film", now - 5 * hour)
        val replayed = resume("film", hour) // watched again after the removal
        assertEquals("back", listOf("film"), ids(plan(inProgress = listOf(replayed), removed = removed)))
    }

    @Test
    fun `launcher removal never changes the in-app source lists`() {
        val cache = listOf(resume("film", day))
        val before = cache.toList()
        val removed = LauncherRemovalPolicy.recordRemoval(emptyMap(), "wn_film", now)
        plan(inProgress = cache, removed = removed)
        assertEquals("cache untouched, so the in-app row still has it", before, cache)
    }

    // --- switches ---

    @Test
    fun `include next episodes off keeps only half-watched items`() {
        val items = plan(
            inProgress = listOf(resume("film", day)),
            nextUp = listOf(next("plain", day), next("alert", day, releaseTimestamp = now - hour, isReleaseAlert = true)),
            settings = settings(includeNextUp = false)
        )
        assertEquals("only the resume", listOf("film"), ids(items))
    }

    // --- order ---

    @Test
    fun `most recent first keeps the old recency order`() {
        val items = plan(
            inProgress = listOf(resume("film-1d", day), resume("done-3h", 3 * hour, position = 3_500_000L)),
            nextUp = listOf(next("next-2h", 2 * hour))
        )
        assertEquals("recency", listOf("next-2h", "done-3h", "film-1d"), ids(items))
        assertTrue("engagement is the real time", items.all { it.engagementTimeMs == it.progress.lastWatched })
    }

    @Test
    fun `pick up where I left off orders the launcher by rank and keeps the engagement times descending`() {
        val items = plan(
            inProgress = listOf(resume("film-1d", day), resume("done-3h", 3 * hour, position = 3_500_000L)),
            nextUp = listOf(next("next-2h", 2 * hour)),
            settings = settings(sortMode = ContinueWatchingSortMode.RESUME_FIRST)
        )
        assertEquals("rank", listOf("film-1d", "next-2h", "done-3h"), ids(items))
        val times = items.map { it.engagementTimeMs }
        assertEquals("strictly descending", times.sortedDescending().distinct(), times)
        assertTrue("never later than the real time", items.all { it.engagementTimeMs <= it.progress.lastWatched })
    }

    @Test
    fun `engagement times are stable between runs so an unchanged list is not rewritten`() {
        val args = {
            plan(
                inProgress = listOf(resume("film-1d", day), resume("done-3h", 3 * hour, position = 3_500_000L)),
                nextUp = listOf(next("next-2h", 2 * hour)),
                settings = settings(sortMode = ContinueWatchingSortMode.RESUME_FIRST),
                at = now
            ).map { it.engagementTimeMs }
        }
        assertEquals("deterministic", args(), args())
    }

    @Test
    fun `a resume wins over a next-up of the same show and dismissed next-ups stay out`() {
        val items = plan(
            inProgress = listOf(resume("show", day, season = 1, episode = 1)),
            nextUp = listOf(next("show", hour), next("dismissed", hour)),
            settings = settings(dismissed = setOf("dismissed"))
        )
        assertEquals("one card per show", listOf("show"), ids(items))
        assertEquals("the resume", Kind.Resume, items.single().kind)
    }
}
