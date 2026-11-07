package com.nuvio.tv.core.sports.detail

import kotlinx.coroutines.delay

class FakeSource(
    override val id: SourceId,
    private val onLoad: suspend (GameRef, Set<RefreshPart>) -> SourceLoad,
) : BlockSource {
    var calls = 0
    var lastParts: Set<RefreshPart> = emptySet()
    override suspend fun load(ref: GameRef, parts: Set<RefreshPart>, mapping: SportsMapping): SourceLoad {
        calls++; lastParts = parts
        return onLoad(ref, parts)
    }
}

class RecordingTelemetry : SportsDetailTelemetry {
    val events = mutableListOf<SportsDetailEvent>()
    override fun record(event: SportsDetailEvent) { events += event }
}

class FakeClock(var now: Long = 1_800_000_000_000L) { fun read() = now }

class FakeHttp(private val route: (String) -> HttpResponse) : HttpFetcher {
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    override suspend fun get(url: String, headers: Map<String, String>, deadlineMs: Long): HttpResponse {
        requests += url to headers
        return route(url)
    }
}

fun stats(src: SourceId, vararg rows: Pair<String, String>) =
    GameBlock.Stats(src, rows.map { StatRow(it.first, it.second, it.second) })

fun nbaRef(id: String = "tsdb:100") = GameRef(
    gameId = id, sport = "Basketball", leagueId = "4387", leagueName = "NBA",
    homeName = "Atlanta Hawks", awayName = "Boston Celtics", startsAtMs = 1_800_000_000_000L,
    seedHome = Side("Atlanta Hawks", score = "10"), seedAway = Side("Boston Celtics", score = "7"),
)

suspend fun slow(ms: Long): SourceLoad { delay(ms); return SourceLoad.miss() }
