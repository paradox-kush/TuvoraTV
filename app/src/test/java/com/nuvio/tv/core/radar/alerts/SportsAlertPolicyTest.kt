package com.nuvio.tv.core.radar.alerts

import com.nuvio.tv.core.radar.ScoreSpoilerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every case of the spec's "Required tests" list. JUnit order: assertEquals(message, expected, actual). */
class SportsAlertPolicyTest {
    private val t0 = 1_800_000_000_000L
    private val noon = 12 * 60

    private fun alert(
        kind: AlertKind = AlertKind.Score,
        key: String = "k1",
        eventId: String = "e1",
        sport: String = "soccer",
        leagueId: String? = "L1",
        league: String? = null,
        homeId: String? = "H1",
        awayId: String? = "A1",
        home: String = "Arsenal",
        away: String = "Chelsea",
        homeScore: Int? = 1,
        awayScore: Int? = 0,
        progress: String? = null,
        corrected: Boolean = false,
        atMs: Long = t0,
    ) = SportsAlert(key, eventId, kind, sport, leagueId, league, homeId, awayId, home, away, homeScore, awayScore, progress, corrected, atMs)

    private fun ctx(
        nowMs: Long = t0,
        minute: Int = noon,
        playback: PlaybackContext = PlaybackContext.None,
        open: String? = null,
        playing: Set<String> = emptySet(),
        watch: Map<String, WatchState> = emptyMap(),
        leagues: Set<String> = emptySet(),
        teams: Set<String> = setOf("H1"),
        spoiler: ScoreSpoilerSettings = ScoreSpoilerSettings(),
        revealed: Set<String> = emptySet(),
    ) = AlertContext(nowMs, minute, playback, open, playing, watch, leagues, teams, spoiler, revealed)

    /** Batching off by default in these tests so a decision is about ONE gate; batch tests turn it on. */
    private fun settings(
        batch: Int = 0,
        cap: Int = 0,
        teamLevel: AlertLevel = AlertLevel.All,
        leagueLevel: AlertLevel = AlertLevel.FinalOnly,
    ) = SportsAlertSettings(batchSeconds = batch, maxPerHour = cap, teamLevel = teamLevel, leagueLevel = leagueLevel)

    private fun decide(a: SportsAlert, s: SportsAlertSettings = settings(), c: AlertContext = ctx(), l: AlertLedger = AlertLedger()) =
        SportsAlertPolicy.onAlert(a, s, c, l)

    private fun dropped(r: DropReason) = AlertDecision.Drop(r)

    private fun shown(d: AlertDecision): AlertBanner = (d as AlertDecision.ShowNow).banner

    // ---- gate 1 ----

    @Test
    fun `defaults show a followed team's goal`() {
        val d = decide(alert(league = "Premier League", progress = "67'"), SportsAlertSettings()).first
        val b = shown(d)
        assertEquals("title", "Goal · Arsenal 1–0 Chelsea", b.title)
        assertEquals("detail is league and progress", "Premier League · 67'", b.detail)
        assertEquals("event id", "e1", b.eventId)
    }

    @Test
    fun `disabled drops everything`() {
        assertEquals("disabled", dropped(DropReason.Disabled), decide(alert(), settings().copy(enabled = false)).first)
    }

    @Test
    fun `a stale alert is dropped and exactly five minutes is not stale`() {
        val old = alert(atMs = t0 - SportsAlertPolicy.MAX_ALERT_AGE_MS - 1)
        assertEquals("older than five minutes", dropped(DropReason.Stale), decide(old).first)
        val edge = alert(atMs = t0 - SportsAlertPolicy.MAX_ALERT_AGE_MS)
        assertTrue("exactly five minutes shows", decide(edge).first is AlertDecision.ShowNow)
    }

    @Test
    fun `a key seen before is a duplicate`() {
        val (first, ledger) = decide(alert())
        assertTrue("first shows", first is AlertDecision.ShowNow)
        assertEquals("same key again", dropped(DropReason.Duplicate), decide(alert(), l = ledger).first)
    }

    @Test
    fun `the seen-key memory is a 200 key LRU`() {
        var ledger = AlertLedger()
        repeat(201) { i -> ledger = decide(alert(key = "k$i"), l = ledger).second }
        assertEquals("capped", 200, ledger.seenKeys.size)
        assertTrue("the oldest key is evicted", "k0" !in ledger.seenKeys)
        assertTrue("the newest key is kept", "k200" in ledger.seenKeys)
    }

    // ---- gate 2 ----

    @Test
    fun `an unfollowed game is dropped`() {
        assertEquals("not followed", dropped(DropReason.NotFollowed), decide(alert(), c = ctx(teams = emptySet())).first)
    }

    @Test
    fun `a league follower at default gets the final only`() {
        val c = ctx(leagues = setOf("L1"), teams = emptySet())
        assertEquals("goal", dropped(DropReason.KindOff), decide(alert(AlertKind.Score), c = c).first)
        assertEquals("start", dropped(DropReason.KindOff), decide(alert(AlertKind.Start, key = "s"), c = c).first)
        assertTrue("final", decide(alert(AlertKind.Final, key = "f"), c = c).first is AlertDecision.ShowNow)
    }

    @Test
    fun `team level beats league level when a followed team plays`() {
        val c = ctx(leagues = setOf("L1"))
        assertTrue("team all, league final: goal shows", decide(alert(), settings(teamLevel = AlertLevel.All, leagueLevel = AlertLevel.FinalOnly), c).first is AlertDecision.ShowNow)
        assertEquals(
            "team final, league all: goal is off", dropped(DropReason.KindOff),
            decide(alert(), settings(teamLevel = AlertLevel.FinalOnly, leagueLevel = AlertLevel.All), c).first,
        )
    }

    @Test
    fun `levels include the right kinds`() {
        assertEquals("all", listOf(true, true, true), AlertKind.entries.map { AlertLevel.All.includes(it) })
        assertEquals("start+final", listOf(true, false, true), AlertKind.entries.map { AlertLevel.StartAndFinal.includes(it) })
        assertEquals("final", listOf(false, false, true), AlertKind.entries.map { AlertLevel.FinalOnly.includes(it) })
        assertEquals("off", listOf(false, false, false), AlertKind.entries.map { AlertLevel.Off.includes(it) })
    }

    @Test
    fun `a per-game off mutes and a per-game all lifts a league follower to everything`() {
        val off = settings().copy(games = listOf(GameAlertOverride("e1", "off", t0 + 1000)))
        assertEquals("off", dropped(DropReason.Muted), decide(alert(), off).first)
        val all = settings().copy(games = listOf(GameAlertOverride("e1", "all", t0 + 1000)))
        val leagueOnly = ctx(leagues = setOf("L1"), teams = emptySet())
        assertTrue("all", decide(alert(), all, leagueOnly).first is AlertDecision.ShowNow)
        val expired = settings().copy(games = listOf(GameAlertOverride("e1", "off", t0 - 1)))
        assertTrue("an expired override is ignored", decide(alert(), expired).first is AlertDecision.ShowNow)
    }

    @Test
    fun `a muted team or league is muted and a muted team falls back to the league level`() {
        assertEquals("muted team", dropped(DropReason.Muted), decide(alert(), settings().copy(mutedTeams = setOf("H1"))).first)
        val leagueOnly = ctx(leagues = setOf("L1"), teams = emptySet())
        assertEquals("muted league", dropped(DropReason.Muted), decide(alert(AlertKind.Final), settings().copy(mutedLeagues = setOf("L1")), leagueOnly).first)
        val both = ctx(leagues = setOf("L1"))
        assertEquals(
            "team muted, league followed: league level (final only) applies", dropped(DropReason.KindOff),
            decide(alert(), settings().copy(mutedTeams = setOf("H1")), both).first,
        )
    }

    // ---- gate 3 ----

    @Test
    fun `the open game and the game being watched are dropped`() {
        assertEquals("game page open", dropped(DropReason.WatchingThisGame), decide(alert(), c = ctx(open = "e1")).first)
        val onIt = ctx(playing = setOf("c1"), watch = mapOf("e1" to WatchState.Channel(setOf("c1"))))
        assertEquals("watching its channel", dropped(DropReason.WatchingThisGame), decide(alert(), c = onIt).first)
        assertTrue("another game's page does not matter", decide(alert(), c = ctx(open = "e2")).first is AlertDecision.ShowNow)
    }

    // ---- gate 4 ----

    @Test
    fun `quiet hours 22 to 08 drop inside and show at the edges`() {
        fun at(minute: Int) = decide(alert(), c = ctx(minute = minute)).first
        assertEquals("23:30", dropped(DropReason.QuietHours), at(23 * 60 + 30))
        assertEquals("22:00", dropped(DropReason.QuietHours), at(22 * 60))
        assertEquals("07:59", dropped(DropReason.QuietHours), at(7 * 60 + 59))
        assertTrue("08:00", at(8 * 60) is AlertDecision.ShowNow)
        assertTrue("21:59", at(21 * 60 + 59) is AlertDecision.ShowNow)
    }

    @Test
    fun `a window that does not cross midnight`() {
        val s = settings().copy(quietStartMin = 600, quietEndMin = 700)
        fun at(minute: Int) = decide(alert(), s, ctx(minute = minute)).first
        assertEquals("inside", dropped(DropReason.QuietHours), at(650))
        assertEquals("start is inside", dropped(DropReason.QuietHours), at(600))
        assertTrue("end is outside", at(700) is AlertDecision.ShowNow)
        assertTrue("before", at(599) is AlertDecision.ShowNow)
        assertTrue("after midnight is outside a daytime window", at(60) is AlertDecision.ShowNow)
    }

    @Test
    fun `start equal to end means no quiet hours and off means off`() {
        val equal = settings().copy(quietStartMin = 600, quietEndMin = 600)
        assertTrue("start == end", decide(alert(), equal, ctx(minute = 600)).first is AlertDecision.ShowNow)
        val off = settings().copy(quietOn = false)
        assertTrue("quiet off", decide(alert(), off, ctx(minute = 23 * 60)).first is AlertDecision.ShowNow)
    }

    // ---- gate 5 ----

    @Test
    fun `live tv only passes none and live tv and drops vod`() {
        val s = settings().copy(duringPlayback = DuringPlayback.LiveTvOnly)
        assertTrue("none", decide(alert(), s, ctx(playback = PlaybackContext.None)).first is AlertDecision.ShowNow)
        assertEquals("vod", dropped(DropReason.DuringPlayback), decide(alert(), s, ctx(playback = PlaybackContext.Vod)).first)
        assertTrue("live tv", decide(alert(), s, ctx(playback = PlaybackContext.LiveTv)).first is AlertDecision.ShowNow)
    }

    @Test
    fun `never drops any playback and always drops none`() {
        val never = settings().copy(duringPlayback = DuringPlayback.Never)
        assertTrue("never + none", decide(alert(), never, ctx(playback = PlaybackContext.None)).first is AlertDecision.ShowNow)
        assertEquals("never + live", dropped(DropReason.DuringPlayback), decide(alert(), never, ctx(playback = PlaybackContext.LiveTv)).first)
        assertEquals("never + vod", dropped(DropReason.DuringPlayback), decide(alert(), never, ctx(playback = PlaybackContext.Vod)).first)
        val always = settings().copy(duringPlayback = DuringPlayback.Always)
        assertTrue("always + vod", decide(alert(), always, ctx(playback = PlaybackContext.Vod)).first is AlertDecision.ShowNow)
    }

    // ---- gate 6: hidden scores ----

    private val hidden = ScoreSpoilerSettings(shown = false)

    @Test
    fun `hidden scores drop a score alert show the start and show a final without numbers`() {
        assertEquals("score", dropped(DropReason.ScoresHidden), decide(alert(AlertKind.Score), c = ctx(spoiler = hidden)).first)
        val start = shown(decide(alert(AlertKind.Start, key = "s", homeScore = null, awayScore = null), c = ctx(spoiler = hidden)).first)
        assertEquals("start", "Arsenal v Chelsea has started", start.title)
        val final = shown(decide(alert(AlertKind.Final, key = "f", homeScore = 2, awayScore = 1), c = ctx(spoiler = hidden)).first)
        assertEquals("final has no numbers", "Full time · Arsenal v Chelsea", final.title)
    }

    @Test
    fun `a revealed game shows its numbers while scores are hidden`() {
        val c = ctx(spoiler = hidden, revealed = setOf("e1"))
        assertEquals("score", "Goal · Arsenal 1–0 Chelsea", shown(decide(alert(), c = c).first).title)
        assertEquals("final", "Full time · Arsenal 2–1 Chelsea", shown(decide(alert(AlertKind.Final, key = "f", homeScore = 2, awayScore = 1), c = c).first).title)
        val other = ctx(spoiler = hidden, revealed = setOf("e2"))
        assertEquals("another game's reveal does not count", dropped(DropReason.ScoresHidden), decide(alert(), c = other).first)
    }

    // ---- gate 6: delay ----

    private val delayed = ScoreSpoilerSettings(shown = true, delaySeconds = 30)

    @Test
    fun `a delay holds a score and a final until received plus the delay and never holds the start`() {
        val c = ctx(spoiler = delayed)
        assertEquals("score", AlertDecision.HoldUntil(t0 + 30_000, HoldReason.Delay), decide(alert(), c = c).first)
        assertEquals("final", AlertDecision.HoldUntil(t0 + 30_000, HoldReason.Delay), decide(alert(AlertKind.Final, key = "f"), c = c).first)
        assertTrue("start", decide(alert(AlertKind.Start, key = "s"), c = c).first is AlertDecision.ShowNow)
    }

    @Test
    fun `a delayed alert shows at release`() {
        val c = ctx(spoiler = delayed)
        val (_, held) = decide(alert(), c = c)
        assertEquals("timer due", t0 + 30_000, SportsAlertPolicy.nextTimerAtMs(held))
        val (early, stillHeld) = SportsAlertPolicy.onTimer(settings(), ctx(nowMs = t0 + 29_999, spoiler = delayed), held)
        assertNull("not yet", early)
        assertEquals("still held", 1, stillHeld.pending.size)
        val (banner, after) = SportsAlertPolicy.onTimer(settings(), ctx(nowMs = t0 + 30_000, spoiler = delayed), held)
        assertEquals("released", "Goal · Arsenal 1–0 Chelsea", banner?.title)
        assertTrue("nothing left", after.pending.isEmpty())
        assertNull("no timer", SportsAlertPolicy.nextTimerAtMs(after))
    }

    @Test
    fun `a held alert is dropped at release if the game page was opened or scores got hidden`() {
        val (_, held) = decide(alert(), c = ctx(spoiler = delayed))
        val opened = SportsAlertPolicy.onTimer(settings(), ctx(nowMs = t0 + 30_000, spoiler = delayed, open = "e1"), held)
        assertNull("game page opened", opened.first)
        assertTrue("and it is gone", opened.second.pending.isEmpty())
        val nowHidden = SportsAlertPolicy.onTimer(settings(), ctx(nowMs = t0 + 30_000, spoiler = hidden), held)
        assertNull("scores hidden meanwhile", nowHidden.first)
        val quiet = SportsAlertPolicy.onTimer(settings(), ctx(nowMs = t0 + 30_000, spoiler = delayed, minute = 23 * 60), held)
        assertNull("quiet hours began meanwhile", quiet.first)
    }

    // ---- gate 7: cap ----

    @Test
    fun `the hourly cap drops the N plus first and frees after an hour`() {
        val s = settings(cap = 3)
        var ledger = AlertLedger()
        repeat(3) { i ->
            val (d, l) = decide(alert(key = "k$i", eventId = "e$i", atMs = t0 + i), s, ctx(nowMs = t0 + i), ledger)
            assertTrue("banner $i shows", d is AlertDecision.ShowNow)
            ledger = l
        }
        val (fourth, l4) = decide(alert(key = "k3", atMs = t0 + 3), s, ctx(nowMs = t0 + 3), ledger)
        assertEquals("fourth is capped", dropped(DropReason.HourlyCap), fourth)
        val later = t0 + SportsAlertPolicy.CAP_WINDOW_MS + 3
        val (again, _) = decide(alert(key = "k4", atMs = later), s, ctx(nowMs = later), l4)
        assertTrue("an hour later it frees", again is AlertDecision.ShowNow)
    }

    @Test
    fun `a cap of zero means no limit`() {
        var ledger = AlertLedger()
        repeat(30) { i -> ledger = decide(alert(key = "k$i", atMs = t0 + i), settings(cap = 0), ctx(nowMs = t0 + i), ledger).second }
        assertEquals("all shown", 30, ledger.shownAtMs.size)
    }

    // ---- gate 8: batching ----

    private val batch = settings(batch = 60)

    private fun two(): Pair<AlertDecision, AlertLedger> {
        val (_, l1) = decide(alert(key = "k1", atMs = t0), batch, ctx(nowMs = t0))
        return decide(alert(key = "k2", eventId = "e2", atMs = t0 + 10_000, home = "Leeds", away = "Hull", homeId = "H1"), batch, ctx(nowMs = t0 + 10_000), l1)
    }

    @Test
    fun `the first banner is leading edge and the second is held to the window end`() {
        val (second, ledger) = two()
        assertEquals("held", AlertDecision.HoldUntil(t0 + 60_000, HoldReason.Batch), second)
        assertEquals("timer", t0 + 60_000, SportsAlertPolicy.nextTimerAtMs(ledger))
        val (banner, after) = SportsAlertPolicy.onTimer(batch, ctx(nowMs = t0 + 60_000), ledger)
        assertEquals("flushed", "Goal · Leeds 1–0 Hull", banner?.title)
        assertNull("no timer left", SportsAlertPolicy.nextTimerAtMs(after))
        assertEquals("window restarts", t0 + 60_000, after.lastBannerAtMs)
    }

    @Test
    fun `a window that already elapsed shows at once`() {
        val (_, l1) = decide(alert(key = "k1"), batch, ctx())
        val later = t0 + 60_000
        val (d, _) = decide(alert(key = "k2", eventId = "e2", atMs = later), batch, ctx(nowMs = later), l1)
        assertTrue("shows", d is AlertDecision.ShowNow)
    }

    @Test
    fun `batch zero is off`() {
        val (_, l1) = decide(alert(key = "k1"), settings(batch = 0), ctx())
        val (d, _) = decide(alert(key = "k2", eventId = "e2", atMs = t0 + 1), settings(batch = 0), ctx(nowMs = t0 + 1), l1)
        assertTrue("second shows at once", d is AlertDecision.ShowNow)
    }

    @Test
    fun `the same game's held alerts supersede each other and a final beats a score`() {
        var (_, ledger) = decide(alert(key = "k1"), batch, ctx())
        ledger = decide(alert(key = "k2", eventId = "e2", homeScore = 1, atMs = t0 + 5_000), batch, ctx(nowMs = t0 + 5_000), ledger).second
        ledger = decide(alert(key = "k3", eventId = "e2", homeScore = 2, atMs = t0 + 9_000), batch, ctx(nowMs = t0 + 9_000), ledger).second
        val newest = SportsAlertPolicy.onTimer(batch, ctx(nowMs = t0 + 60_000), ledger).first
        assertEquals("newest score of the game", "Goal · Arsenal 2–0 Chelsea", newest?.title)

        var l2 = decide(alert(key = "k1"), batch, ctx()).second
        l2 = decide(alert(AlertKind.Final, key = "k4", eventId = "e2", homeScore = 3, atMs = t0 + 7_000), batch, ctx(nowMs = t0 + 7_000), l2).second
        l2 = decide(alert(key = "k5", eventId = "e2", homeScore = 4, atMs = t0 + 9_000), batch, ctx(nowMs = t0 + 9_000), l2).second
        val final = SportsAlertPolicy.onTimer(batch, ctx(nowMs = t0 + 60_000), l2).first
        assertEquals("a final beats a later score", "Full time · Arsenal 3–0 Chelsea", final?.title)
    }

    @Test
    fun `several games flush as one summary banner of up to three lines`() {
        var ledger = decide(alert(key = "k1"), batch, ctx()).second
        listOf("e2", "e3", "e4", "e5").forEachIndexed { i, id ->
            ledger = decide(alert(key = "k-$id", eventId = id, atMs = t0 + 1_000L * (i + 1)), batch, ctx(nowMs = t0 + 1_000L * (i + 1)), ledger).second
        }
        val banner = SportsAlertPolicy.onTimer(batch, ctx(nowMs = t0 + 60_000), ledger).first
        assertNotNull("summary", banner)
        assertEquals("title", "4 sports updates", banner!!.title)
        assertEquals("up to three lines", 3, banner.lines.size)
        assertTrue("it is a summary", banner.isSummary)
        assertNull("a summary opens Sports, not a game", banner.eventId)
    }

    @Test
    fun `a summary under hidden scores carries no numbers`() {
        val c = { now: Long -> ctx(nowMs = now, spoiler = ScoreSpoilerSettings(shown = false)) }
        var ledger = decide(alert(AlertKind.Final, key = "k1"), batch, c(t0)).second
        ledger = decide(alert(AlertKind.Final, key = "k2", eventId = "e2"), batch, c(t0 + 1_000), ledger).second
        ledger = decide(alert(AlertKind.Final, key = "k3", eventId = "e3"), batch, c(t0 + 2_000), ledger).second
        val banner = SportsAlertPolicy.onTimer(batch, c(t0 + 60_000), ledger).first!!
        assertTrue("no digit in any line", banner.lines.none { line -> line.any(Char::isDigit) })
    }

    @Test
    fun `a batch banner counts once for the cap`() {
        val s = settings(batch = 60, cap = 3)
        var ledger = decide(alert(key = "k1"), s, ctx()).second
        listOf("e2", "e3", "e4").forEachIndexed { i, id ->
            ledger = decide(alert(key = "k-$id", eventId = id, atMs = t0 + 1_000L * (i + 1)), s, ctx(nowMs = t0 + 1_000L * (i + 1)), ledger).second
        }
        val after = SportsAlertPolicy.onTimer(s, ctx(nowMs = t0 + 60_000), ledger).second
        assertEquals("one for the first banner, one for the batch", 2, after.shownAtMs.size)
    }

    @Test
    fun `a held batch is dropped at flush when the cap was reached meanwhile`() {
        val s = settings(batch = 60, cap = 2)
        var ledger = decide(alert(key = "k1"), s, ctx()).second
        ledger = decide(alert(key = "k2", eventId = "e2", atMs = t0 + 1_000), s, ctx(nowMs = t0 + 1_000), ledger).second
        assertEquals("held, not dropped, while the cap has room", 1, ledger.pending.size)
        // Another banner (a released delay) used the last slot while the batch waited.
        val used = ledger.copy(shownAtMs = ledger.shownAtMs + (t0 + 2_000))
        val (banner, after) = SportsAlertPolicy.onTimer(s, ctx(nowMs = t0 + 60_000), used)
        assertNull("cap used up", banner)
        assertTrue("and nothing stays held", after.pending.isEmpty())
    }

    // ---- text ----

    @Test
    fun `texts per kind and sport`() {
        fun title(a: SportsAlert) = shown(decide(a).first).title
        assertEquals("start", "Arsenal v Chelsea has started", title(alert(AlertKind.Start, homeScore = null, awayScore = null)))
        assertEquals("hockey goal", "Goal · Arsenal 1–0 Chelsea", title(alert(sport = "ice_hockey")))
        assertEquals("football score", "Score · Arsenal 1–0 Chelsea", title(alert(sport = "american_football")))
        assertEquals("baseball score", "Score · Arsenal 1–0 Chelsea", title(alert(sport = "baseball")))
        assertEquals("corrected", "Score corrected · Arsenal 1–0 Chelsea", title(alert(corrected = true)))
        assertEquals("final", "Full time · Arsenal 2–1 Chelsea", title(alert(AlertKind.Final, homeScore = 2, awayScore = 1)))
        assertEquals("missing numbers", "Goal · Arsenal v Chelsea", title(alert(homeScore = null, awayScore = null)))
    }

    @Test
    fun `a detail line needs a league or progress`() {
        assertNull("none", shown(decide(alert()).first).detail)
        assertEquals("progress only", "67'", shown(decide(alert(progress = "67'")).first).detail)
    }

    // ---- gate 3: the watch check (review round 1, H3) ----

    private val liveTv = PlaybackContext.LiveTv

    @Test
    fun `live tv playing and the game unknown holds a score and a final for the lookup but never a start`() {
        val c = ctx(playback = liveTv, playing = setOf("c9"))
        val hold = AlertDecision.HoldUntil(t0 + SportsAlertPolicy.WATCH_CHECK_MS, HoldReason.WatchCheck)
        assertEquals("score", hold, decide(alert(), c = c).first)
        assertEquals("final", hold, decide(alert(AlertKind.Final, key = "f"), c = c).first)
        assertTrue("start", decide(alert(AlertKind.Start, key = "s"), c = c).first is AlertDecision.ShowNow)
        assertEquals("the hold is about 6 s", 6_000L, SportsAlertPolicy.WATCH_CHECK_MS)
    }

    @Test
    fun `no hold without live tv, without a playing channel or with a known answer`() {
        assertTrue("nothing playing", decide(alert(), c = ctx(playback = PlaybackContext.None)).first is AlertDecision.ShowNow)
        assertTrue("live tv but no known channel", decide(alert(), c = ctx(playback = liveTv)).first is AlertDecision.ShowNow)
        val none = ctx(playback = liveTv, playing = setOf("c9"), watch = mapOf("e1" to WatchState.NoChannel))
        assertTrue("cached: no channel for this game", decide(alert(), c = none).first is AlertDecision.ShowNow)
        val other = ctx(playback = liveTv, playing = setOf("c9"), watch = mapOf("e1" to WatchState.Channel(setOf("c1"))))
        assertTrue("its channel exists but another one plays", decide(alert(), c = other).first is AlertDecision.ShowNow)
    }

    @Test
    fun `two games on the playing channel are both dropped`() {
        val c = ctx(playback = liveTv, playing = setOf("c1"), watch = mapOf("e1" to WatchState.Channel(setOf("c1")), "e2" to WatchState.Channel(setOf("c1"))))
        assertEquals("first", dropped(DropReason.WatchingThisGame), decide(alert(), c = c).first)
        assertEquals("second game, same channel", dropped(DropReason.WatchingThisGame), decide(alert(key = "k2", eventId = "e2"), c = c).first)
    }

    private fun held(): AlertLedger = decide(alert(), c = ctx(playback = liveTv, playing = setOf("c1"))).second

    @Test
    fun `a lookup answer releases the hold at once and re-runs the gates`() {
        val noChannel = ctx(nowMs = t0 + 2_000, playback = liveTv, playing = setOf("c1"), watch = mapOf("e1" to WatchState.NoChannel))
        val shown = SportsAlertPolicy.onTimer(settings(), noChannel, held(), setOf("e1"))
        assertEquals("no channel for it: shown", "Goal \u00B7 Arsenal 1\u20130 Chelsea", shown.first?.title)
        val onIt = ctx(nowMs = t0 + 2_000, playback = liveTv, playing = setOf("c1"), watch = mapOf("e1" to WatchState.Channel(setOf("c1"))))
        val dropped = SportsAlertPolicy.onTimer(settings(), onIt, held(), setOf("e1"))
        assertNull("watching it: dropped", dropped.first)
        assertEquals("and counted", 1, dropped.second.counts["dropped:watching_this_game"])
    }

    @Test
    fun `an unanswered check is untouched before its deadline`() {
        val early = ctx(nowMs = t0 + 5_999, playback = liveTv, playing = setOf("c1"))
        val r = SportsAlertPolicy.onTimer(settings(), early, held())
        assertNull("not yet", r.first)
        assertEquals("still held", 1, r.second.pending.size)
        assertEquals("watch check ids", setOf("e1"), SportsAlertPolicy.watchCheckEventIds(r.second))
    }

    @Test
    fun `still unknown at the deadline drops a score and shows a final without numbers`() {
        val deadline = ctx(nowMs = t0 + 6_000, playback = liveTv, playing = setOf("c1"))
        val score = SportsAlertPolicy.onTimer(settings(), deadline, held())
        assertNull("score dropped", score.first)
        assertEquals("counted as watch_unknown", 1, score.second.counts["dropped:watch_unknown"])
        val finalHeld = decide(alert(AlertKind.Final, key = "f", homeScore = 2, awayScore = 1), c = ctx(playback = liveTv, playing = setOf("c1"))).second
        val final = SportsAlertPolicy.onTimer(settings(), deadline, finalHeld)
        assertEquals("final without numbers", "Full time \u00B7 Arsenal v Chelsea", final.first?.title)
    }

    @Test
    fun `a released check still honours the score delay and the batch window`() {
        val answered = ctx(nowMs = t0 + 1_000, playback = liveTv, playing = setOf("c1"), watch = mapOf("e1" to WatchState.NoChannel), spoiler = delayed)
        val r = SportsAlertPolicy.onTimer(settings(), answered, held(), setOf("e1"))
        assertNull("not shown yet", r.first)
        assertEquals("re-held as a delay", HoldReason.Delay, r.second.pending.single().reason)
        assertEquals("the delay counts from receipt, not from the answer", t0 + 30_000, r.second.pending.single().dueAtMs)
    }

    // ---- clock skew, mute vs all, flush re-checks, counts ----

    @Test
    fun `an at far ahead of this clock is skew and never stale or dropped`() {
        assertTrue("two minutes ahead", decide(alert(atMs = t0 + 120_000)).first is AlertDecision.ShowNow)
        assertTrue("hours ahead", decide(alert(key = "k2", atMs = t0 + 3_600_000)).first is AlertDecision.ShowNow)
        assertEquals("a genuinely old one is still stale", dropped(DropReason.Stale), decide(alert(key = "k3", atMs = t0 - 400_000)).first)
    }

    @Test
    fun `a mute wins over a per-game all`() {
        val s = settings().copy(mutedTeams = setOf("H1"), games = listOf(GameAlertOverride("e1", "all", t0 + 1_000)))
        assertEquals("muted", dropped(DropReason.Muted), decide(alert(), s).first)
    }

    @Test
    fun `held alerts re-run gate two at flush`() {
        val (_, l) = decide(alert(), c = ctx(spoiler = delayed))
        val unfollowed = SportsAlertPolicy.onTimer(settings(), ctx(nowMs = t0 + 30_000, spoiler = delayed, teams = emptySet()), l)
        assertNull("no longer followed", unfollowed.first)
        val muted = SportsAlertPolicy.onTimer(settings().copy(mutedTeams = setOf("H1")), ctx(nowMs = t0 + 30_000, spoiler = delayed), l)
        assertNull("muted meanwhile", muted.first)
        val off = SportsAlertPolicy.onTimer(settings().copy(enabled = false), ctx(nowMs = t0 + 30_000, spoiler = delayed), l)
        assertNull("switched off meanwhile", off.first)
    }

    @Test
    fun `count-only drops are counted and the never-emit ones are not`() {
        val quiet = decide(alert(), c = ctx(minute = 23 * 60)).second
        assertEquals("quiet hours counted", 1, quiet.counts["dropped:quiet_hours"])
        val notFollowed = decide(alert(), c = ctx(teams = emptySet())).second
        assertTrue("not_followed is never counted", notFollowed.counts.isEmpty())
        val kindOff = decide(alert(AlertKind.Start, key = "s"), settings(teamLevel = AlertLevel.FinalOnly)).second
        assertTrue("kind_off is never counted", kindOff.counts.isEmpty())
    }

    @Test
    fun `superseded alerts are counted at flush`() {
        var ledger = decide(alert(key = "k1"), batch, ctx()).second
        ledger = decide(alert(key = "k2", eventId = "e2", homeScore = 1, atMs = t0 + 5_000), batch, ctx(nowMs = t0 + 5_000), ledger).second
        ledger = decide(alert(key = "k3", eventId = "e2", homeScore = 2, atMs = t0 + 9_000), batch, ctx(nowMs = t0 + 9_000), ledger).second
        val after = SportsAlertPolicy.onTimer(batch, ctx(nowMs = t0 + 60_000), ledger).second
        assertEquals("one superseded", 1, after.counts["dropped:superseded"])
    }

    @Test
    fun `a blank away is shown as the home name alone`() {
        val t = shown(decide(alert(away = "", homeScore = 1, awayScore = 0)).first).title
        assertEquals("home only", "Goal \u00B7 Arsenal", t)
        assertEquals("start", "Arsenal has started", shown(decide(alert(AlertKind.Start, key = "s", away = "")).first).title)
    }

    // ---- review round 2 ----

    @Test
    fun `playing a second carrier of the game drops the alert`() {
        val c = ctx(playback = liveTv, playing = setOf("c2"), watch = mapOf("e1" to WatchState.Channel(setOf("c1", "c2"))))
        assertEquals("any matched channel counts", dropped(DropReason.WatchingThisGame), decide(alert(), c = c).first)
    }

    @Test
    fun `a hold released far overdue or older than max age plus the delay is dropped uncounted`() {
        val (_, l) = decide(alert(), c = ctx(spoiler = delayed))
        val overdue = SportsAlertPolicy.onTimer(settings(), ctx(nowMs = t0 + 30_000 + 60_001, spoiler = delayed), l)
        assertNull("more than 60 s late", overdue.first)
        assertTrue("and nothing stays held", overdue.second.pending.isEmpty())
        assertTrue("uncounted, like stale", overdue.second.counts.keys.none { it.startsWith("dropped") })
        val old = AlertLedger(pending = listOf(Held(alert(), t0 + 331_000, HoldReason.Delay, t0)))
        val tooOld = SportsAlertPolicy.onTimer(settings(), ctx(nowMs = t0 + 331_000, spoiler = delayed), old)
        assertNull("received longer ago than max age + delay", tooOld.first)
        val fresh = AlertLedger(pending = listOf(Held(alert(), t0 + 329_000, HoldReason.Delay, t0)))
        assertNotNull("just inside", SportsAlertPolicy.onTimer(settings(), ctx(nowMs = t0 + 329_000, spoiler = delayed), fresh).first)
    }

    @Test
    fun `a delay release on live tv with an unknown channel goes to the watch check and never shows numbers`() {
        val (_, l) = decide(alert(), c = ctx(spoiler = delayed))
        val live = ctx(nowMs = t0 + 30_000, playback = liveTv, playing = setOf("c1"), spoiler = delayed)
        val released = SportsAlertPolicy.onTimer(settings(), live, l)
        assertNull("not shown", released.first)
        val hold = released.second.pending.single()
        assertEquals("a watch check", HoldReason.WatchCheck, hold.reason)
        assertEquals("from now", t0 + 30_000 + 6_000, hold.dueAtMs)
        assertEquals("receipt kept", t0, hold.receivedAtMs)
        val deadline = SportsAlertPolicy.onTimer(settings(), ctx(nowMs = t0 + 36_000, playback = liveTv, playing = setOf("c1"), spoiler = delayed), released.second)
        assertNull("still unknown: a score is dropped", deadline.first)
        assertEquals("watch_unknown", 1, deadline.second.counts["dropped:watch_unknown"])
    }

    @Test
    fun `a batch release on live tv with an unknown channel goes to the watch check too`() {
        var ledger = decide(alert(key = "k1"), batch, ctx()).second
        ledger = decide(alert(key = "k2", eventId = "e2", atMs = t0 + 1_000), batch, ctx(nowMs = t0 + 1_000), ledger).second
        val live = ctx(nowMs = t0 + 60_000, playback = liveTv, playing = setOf("c1"))
        val r = SportsAlertPolicy.onTimer(batch, live, ledger)
        assertNull("not shown", r.first)
        assertEquals("a watch check", HoldReason.WatchCheck, r.second.pending.single().reason)
    }

    @Test
    fun `the score delay counts from receipt, not from the watch check release`() {
        val checkHeld = AlertLedger(pending = listOf(Held(alert(), t0 + 6_000, HoldReason.WatchCheck, t0)))
        val late = ctx(nowMs = t0 + 40_000, playback = liveTv, playing = setOf("c1"), watch = mapOf("e1" to WatchState.NoChannel), spoiler = delayed)
        val shown = SportsAlertPolicy.onTimer(settings(), late, checkHeld, setOf("e1"))
        assertNotNull("receipt + 30 s already passed: shown at once", shown.first)
        val early = ctx(nowMs = t0 + 7_000, playback = liveTv, playing = setOf("c1"), watch = mapOf("e1" to WatchState.NoChannel), spoiler = delayed)
        val held = SportsAlertPolicy.onTimer(settings(), early, checkHeld, setOf("e1"))
        assertEquals("still waiting for receipt + 30 s", t0 + 30_000, held.second.pending.single().dueAtMs)
    }

    @Test
    fun `a final downgraded at the watch deadline still honours the delay`() {
        val finalHeld = AlertLedger(pending = listOf(Held(alert(AlertKind.Final, key = "f", homeScore = 2, awayScore = 1), t0 + 6_000, HoldReason.WatchCheck, t0)))
        val deadline = ctx(nowMs = t0 + 6_000, playback = liveTv, playing = setOf("c1"), spoiler = delayed)
        val r = SportsAlertPolicy.onTimer(settings(), deadline, finalHeld)
        assertNull("not shown yet", r.first)
        val hold = r.second.pending.single()
        assertEquals("delay hold", HoldReason.Delay, hold.reason)
        assertEquals("from receipt", t0 + 30_000, hold.dueAtMs)
        assertNull("numbers stripped", hold.alert.homeScore)
    }

    @Test
    fun `the banner carries the sport slug and a summary a slug or other`() {
        assertEquals("slug", "ice_hockey", shown(decide(alert(sport = "Ice Hockey")).first).sport)
        assertEquals("unknown sport", "other", shown(decide(alert(key = "k2", sport = "Curling")).first).sport)
        var ledger = decide(alert(key = "k1"), batch, ctx()).second
        ledger = decide(alert(key = "k2", eventId = "e2", sport = "soccer", atMs = t0 + 1_000), batch, ctx(nowMs = t0 + 1_000), ledger).second
        ledger = decide(alert(key = "k3", eventId = "e3", sport = "baseball", atMs = t0 + 2_000), batch, ctx(nowMs = t0 + 2_000), ledger).second
        assertEquals("mixed sports", "other", SportsAlertPolicy.onTimer(batch, ctx(nowMs = t0 + 60_000), ledger).first?.sport)
        var same = decide(alert(key = "k1"), batch, ctx()).second
        same = decide(alert(key = "k2", eventId = "e2", atMs = t0 + 1_000), batch, ctx(nowMs = t0 + 1_000), same).second
        same = decide(alert(key = "k3", eventId = "e3", atMs = t0 + 2_000), batch, ctx(nowMs = t0 + 2_000), same).second
        assertEquals("one sport", "soccer", SportsAlertPolicy.onTimer(batch, ctx(nowMs = t0 + 60_000), same).first?.sport)
    }
}
