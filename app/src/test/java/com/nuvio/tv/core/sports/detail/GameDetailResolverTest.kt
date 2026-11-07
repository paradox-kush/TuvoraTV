package com.nuvio.tv.core.sports.detail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDetailResolverTest {
    private val clock = FakeClock()
    private val telemetry = RecordingTelemetry()
    private var mapping = SportsFixtures.mapping

    private fun resolver(tsdb: FakeSource?, espn: FakeSource?) = GameDetailResolver(
        mappingProvider = { mapping },
        sources = listOfNotNull(tsdb, espn).associateBy { it.id },
        telemetry = telemetry,
        clock = clock::read,
    )

    private fun ok(vararg b: GameBlock, header: GameHeader? = null, state: GameState? = null) =
        SourceLoad.ok(b.associateBy { it.type }, header, state)

    private val lineEspn = GameBlock.Linescore(SourceId.ESPN, listOf("Q1"), listOf(listOf("1"), listOf("2")))

    @Test fun `primary source wins a block both can fill`() = runBlocking {
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> ok(stats(SourceId.TSDB, "Shots" to "9")) }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> ok(stats(SourceId.ESPN, "Shots" to "1")) }
        val d = resolver(tsdb, espn).resolve(nbaRef())
        assertEquals("stats from tsdb", SourceId.TSDB, d.sources[BlockType.STATS])
        assertEquals("one stats block", 1, d.blocks.count { it.type == BlockType.STATS })
    }

    @Test fun `espn fills the blocks tsdb lacks`() = runBlocking {
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> ok(stats(SourceId.TSDB, "Shots" to "9")) }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> ok(lineEspn, stats(SourceId.ESPN, "Shots" to "1")) }
        val d = resolver(tsdb, espn).resolve(nbaRef())
        assertEquals("linescore from espn", SourceId.ESPN, d.sources[BlockType.LINESCORE])
        assertEquals("stats stays tsdb", SourceId.TSDB, d.sources[BlockType.STATS])
        assertEquals("partial is fine", Availability.FULL, d.availability)
    }

    @Test fun `a failing espn never blanks tsdb blocks`() = runBlocking {
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> ok(stats(SourceId.TSDB, "Shots" to "9")) }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> error("boom") }
        val d = resolver(tsdb, espn).resolve(nbaRef())
        assertEquals("tsdb stats survive", SourceId.TSDB, d.sources[BlockType.STATS])
        assertEquals("espn marked failed", SourceStatus.FAILED, d.sourceStatus[SourceId.ESPN])
    }

    @Test fun `every source failing is FAILED not an empty match sheet`() = runBlocking {
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.failed(FetchOutcome.HTTP_FAIL) }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> SourceLoad.failed(FetchOutcome.PARSE_FAIL) }
        val d = resolver(tsdb, espn).resolve(nbaRef())
        assertEquals("availability", Availability.FAILED, d.availability)
        assertTrue("no blocks", d.blocks.isEmpty())
        assertEquals("seed header still shown", "Atlanta Hawks", (d.header as GameHeader.Teams).home.name)
    }

    @Test fun `sources that answer empty give the match sheet`() = runBlocking {
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.miss() }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> SourceLoad.miss() }
        val d = resolver(tsdb, espn).resolve(nbaRef())
        assertEquals("match sheet", Availability.MATCH_SHEET, d.availability)
    }

    @Test fun `an espn miss is remembered per game`() = runBlocking {
        val espn = FakeSource(SourceId.ESPN) { _, _ -> SourceLoad.miss() }
        val r = resolver(FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.miss() }, espn)
        r.resolve(nbaRef("tsdb:1")); r.resolve(nbaRef("tsdb:1")); r.resolve(nbaRef("tsdb:1"))
        assertEquals("asked once", 1, espn.calls)
        r.resolve(nbaRef("tsdb:2"))
        assertEquals("other game asks again", 2, espn.calls)
    }

    @Test fun `a transient failure is retried after a short pause but not every tick`() = runBlocking {
        val espn = FakeSource(SourceId.ESPN) { _, _ -> SourceLoad.failed(FetchOutcome.HTTP_FAIL) }
        val r = resolver(FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.miss() }, espn)
        r.resolve(nbaRef()); clock.now += 10_000; r.resolve(nbaRef())
        assertEquals("still cooling down", 1, espn.calls)
        clock.now += 25_000; r.resolve(nbaRef())
        assertEquals("retried", 2, espn.calls)
    }

    @Test fun `a hit clears the negative cache`() = runBlocking {
        var answer: SourceLoad = SourceLoad.failed(FetchOutcome.HTTP_FAIL)
        val espn = FakeSource(SourceId.ESPN) { _, _ -> answer }
        val r = resolver(null, espn)
        r.resolve(nbaRef()); clock.now += 40_000
        answer = ok(lineEspn); r.resolve(nbaRef())
        r.resolve(nbaRef())
        assertEquals("keeps asking after a hit", 3, espn.calls)
    }

    @Test fun `kill switch removes espn from the chain without a request`() = runBlocking {
        mapping = mapping.copy(killSwitch = KillSwitch(leagues = setOf("4387")))
        val espn = FakeSource(SourceId.ESPN) { _, _ -> ok(lineEspn) }
        val d = resolver(FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.miss() }, espn).resolve(nbaRef())
        assertEquals("never called", 0, espn.calls)
        assertEquals("status", SourceStatus.DISABLED, d.sourceStatus[SourceId.ESPN])
    }

    @Test fun `league chain override puts espn first`() = runBlocking {
        mapping = mapping.copy(leagues = mapping.leagues.map { if (it.tsdbId == "4387") it.copy(chain = listOf(SourceId.ESPN, SourceId.TSDB)) else it })
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> ok(stats(SourceId.TSDB, "A" to "1")) }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> ok(stats(SourceId.ESPN, "A" to "2")) }
        assertEquals("espn first", SourceId.ESPN, resolver(tsdb, espn).resolve(nbaRef()).sources[BlockType.STATS])
    }

    @Test fun `header comes from the primary source of the game`() = runBlocking {
        val tsdbHeader = GameHeader.Teams(Side("T-home", score = "1"), Side("T-away", score = "0"))
        val espnHeader = GameHeader.Teams(Side("E-home", score = "99"), Side("E-away", score = "98"))
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> ok(header = tsdbHeader, state = GameState.LIVE) }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> ok(header = espnHeader, state = GameState.FINAL) }
        val viaTsdb = resolver(tsdb, espn).resolve(nbaRef("tsdb:5"))
        assertEquals("tsdb game keeps tsdb header", "T-home", (viaTsdb.header as GameHeader.Teams).home.name)
        assertEquals("tsdb state", GameState.LIVE, viaTsdb.state)
        val viaEspn = resolver(tsdb, espn).resolve(nbaRef("espn:5"))
        assertEquals("espn game takes espn header", "E-home", (viaEspn.header as GameHeader.Teams).home.name)
    }

    @Test fun `refresh parts are passed through to sources`() = runBlocking {
        val espn = FakeSource(SourceId.ESPN) { _, _ -> SourceLoad.miss() }
        resolver(null, espn).resolve(nbaRef(), setOf(RefreshPart.SCOREBOARD))
        assertEquals("only scoreboard", setOf(RefreshPart.SCOREBOARD), espn.lastParts)
    }

    @Test fun `a source that outlives the deadline counts as a failure`() = runBlocking {
        mapping = mapping.copy(deadlineMs = 50)
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> ok(stats(SourceId.TSDB, "A" to "1")) }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> slow(5_000) }
        val t0 = System.currentTimeMillis()
        val d = resolver(tsdb, espn).resolve(nbaRef())
        assertTrue("returned fast", System.currentTimeMillis() - t0 < 2_000)
        assertEquals("espn failed", SourceStatus.FAILED, d.sourceStatus[SourceId.ESPN])
        assertEquals("tsdb kept", SourceId.TSDB, d.sources[BlockType.STATS])
    }

    @Test fun `asOf is the local fetch time`() = runBlocking {
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> ok(stats(SourceId.TSDB, "A" to "1")) }
        val d = resolver(tsdb, null).resolve(nbaRef())
        assertEquals("fetch time", clock.now, d.asOf[SourceId.TSDB])
    }

    @Test fun `telemetry reports sport block source outcome and duration only`() = runBlocking {
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.failed(FetchOutcome.HTTP_FAIL) }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> ok(lineEspn) }
        resolver(tsdb, espn).resolve(nbaRef())
        val e = telemetry.events
        assertTrue("tsdb http_fail recorded", e.any { it.source == SourceId.TSDB && it.outcome == FetchOutcome.HTTP_FAIL })
        assertTrue("espn linescore hit", e.any { it.source == SourceId.ESPN && it.block == BlockType.LINESCORE && it.outcome == FetchOutcome.HIT })
        assertTrue("espn stats empty", e.any { it.source == SourceId.ESPN && it.block == BlockType.STATS && it.outcome == FetchOutcome.EMPTY })
        assertTrue("sport is the profile", e.all { it.sport == "basketball" })
        val flat = e.joinToString { it.toProperties().toString() }
        assertFalse("no team names", flat.contains("Hawks") || flat.contains("Celtics"))
        assertFalse("no urls", flat.contains("http") && flat.contains("://"))
        assertEquals("closed property set", setOf("sport", "block", "source", "outcome", "duration_ms"), e.first().toProperties().keys)
    }

    @Test fun `merge keeps older blocks when a live tick carries only a subset`() {
        val old = GameDetail.skeleton(nbaRef()).copy(
            blocks = listOf(stats(SourceId.TSDB, "A" to "1"), lineEspn), sources = mapOf(BlockType.STATS to SourceId.TSDB, BlockType.LINESCORE to SourceId.ESPN),
        )
        val newLine = lineEspn.copy(rows = listOf(listOf("9"), listOf("8")))
        val tick = GameDetail.skeleton(nbaRef()).copy(blocks = listOf(newLine), sources = mapOf(BlockType.LINESCORE to SourceId.ESPN))
        val merged = tick.mergeOver(old)
        assertEquals("both blocks", 2, merged.blocks.size)
        assertEquals("newer linescore", listOf("9"), (merged.blocks.first { it.type == BlockType.LINESCORE } as GameBlock.Linescore).rows[0])
        assertEquals("sources tracked", SourceId.TSDB, merged.sources[BlockType.STATS])
    }

    @Test fun `unknown league and sport still returns a match sheet`() = runBlocking {
        val ref = nbaRef().copy(leagueId = "000", sport = "Curling")
        val d = resolver(FakeSource(SourceId.TSDB) { _, _ -> SourceLoad.miss() }, null).resolve(ref)
        assertEquals("match sheet", Availability.MATCH_SHEET, d.availability)
        assertNull("no profile", d.profile)
    }

    @Test fun `blocks follow the sport profile order`() = runBlocking {
        val tsdb = FakeSource(SourceId.TSDB) { _, _ -> ok(stats(SourceId.TSDB, "A" to "1")) }
        val espn = FakeSource(SourceId.ESPN) { _, _ -> ok(lineEspn, GameBlock.WinProbability(SourceId.ESPN, 55.0)) }
        val order = resolver(tsdb, espn).resolve(nbaRef()).blocks.map { it.type }
        val profile = SportsFixtures.mapping.profile("basketball")!!
        assertEquals("sorted by profile", order.sortedBy { profile.orderOf(it) }.map { it.wire }, order.map { it.wire })
        assertEquals("overlay block last", BlockType.WIN_PROBABILITY, order.last())
    }
}
