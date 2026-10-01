package com.nuvio.tv.core.iptv

/**
 * Step 0.3 GOLDEN TABLES — framework-free data, copied VERBATIM (package line aside) into NuvioTV's
 * JUnit tests and mirrored case-for-case in nuvio-web `src/lib/iptv/backupServers.golden.json`.
 * Do not edit a case in one repo only: every platform must make the same decision.
 *
 * Only plain Kotlin here (no kotlin.test / JUnit types), so the assertion-argument-order difference
 * between kotlin.test and JUnit stays in each repo's thin runner, never in the table.
 */
object ServerFailoverGolden {

    const val W: Long = 30L * 60 * 1000   // ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS

    /** One fail-over-able request at [nowMs]; [up] = the server indexes that would answer. */
    data class Step(
        val nowMs: Long,
        val up: Set<Int>,
        val expectedOrder: List<Int>,
        /** The index that served the request; null = every server failed. */
        val expectedServed: Int?,
        val expectedState: ServerFailoverState,
    )

    data class Case(
        val name: String,
        val serverCount: Int,
        val initial: ServerFailoverState,
        val steps: List<Step>,
    )

    private fun st(active: Int = 0, retryAfter: Long? = null) = ServerFailoverState(active, retryAfter)

    val policyCases: List<Case> = listOf(
        Case("main healthy stays on main", 3, st(), listOf(
            Step(1_000, setOf(0, 1, 2), listOf(0, 1, 2), 0, st()),
        )),
        Case("main down moves to backup 1, window holds, then main again after the window", 3, st(), listOf(
            Step(1_000, setOf(1, 2), listOf(0, 1, 2), 1, st(1, 1_000 + W)),
            Step(2_000, setOf(0, 1, 2), listOf(1, 2, 0), 1, st(1, 1_000 + W)),
            Step(1_000 + W - 1, setOf(0, 1, 2), listOf(1, 2, 0), 1, st(1, 1_000 + W)),
            Step(1_000 + W, setOf(0, 1, 2), listOf(0, 1, 2), 0, st()),
        )),
        Case("moving between backups inside a running window keeps the window", 3, st(1, 1_000 + W), listOf(
            Step(5_000, setOf(2), listOf(1, 2, 0), 2, st(2, 1_000 + W)),
        )),
        Case("window elapsed and main still down starts a new window", 3, st(1, 1_000 + W), listOf(
            Step(1_000 + W, setOf(1), listOf(0, 1, 2), 1, st(1, 1_000 + W + W)),
        )),
        Case("every server down keeps the state", 3, st(1, 1_000 + W), listOf(
            Step(3_000, emptySet(), listOf(1, 2, 0), null, st(1, 1_000 + W)),
        )),
        Case("every server down from main keeps main", 2, st(), listOf(
            Step(3_000, emptySet(), listOf(0, 1), null, st()),
        )),
        Case("active backup leads, other backups in list order, main last", 4, st(2, 10_000), listOf(
            Step(9_000, setOf(3), listOf(2, 1, 3, 0), 3, st(3, 10_000)),
        )),
        Case("main answering from a backup's walk returns to main and clears the window", 4, st(2, 10_000), listOf(
            Step(9_000, setOf(0), listOf(2, 1, 3, 0), 0, st()),
        )),
        Case("single server is just main", 1, st(), listOf(
            Step(1_000, setOf(0), listOf(0), 0, st()),
        )),
        Case("an index past a shrunk list resets to main", 2, st(4, 99_000), listOf(
            Step(1_000, setOf(0, 1), listOf(0, 1), 0, st()),
        )),
        Case("a backup index without a window tries main first and starts a window", 3, st(1, null), listOf(
            Step(7_000, setOf(1), listOf(0, 1, 2), 1, st(1, 7_000 + W)),
        )),
    )

    /** (kind, httpStatus, shouldFailOver). */
    val classifierCases: List<Triple<FailoverFailureKind, Int?, Boolean>> = listOf(
        Triple(FailoverFailureKind.DNS, null, true),
        Triple(FailoverFailureKind.CONNECT_REFUSED, null, true),
        Triple(FailoverFailureKind.CONNECT_TIMEOUT, null, true),
        Triple(FailoverFailureKind.READ_TIMEOUT, null, true),
        Triple(FailoverFailureKind.TLS_HANDSHAKE, null, true),
        Triple(FailoverFailureKind.HOST_UNAVAILABLE, null, true),
        Triple(FailoverFailureKind.HTTP_STATUS, 500, true),
        Triple(FailoverFailureKind.HTTP_STATUS, 502, true),
        Triple(FailoverFailureKind.HTTP_STATUS, 503, true),
        Triple(FailoverFailureKind.HTTP_STATUS, 504, true),
        Triple(FailoverFailureKind.HTTP_STATUS, 521, true),
        Triple(FailoverFailureKind.HTTP_STATUS, 522, true),
        Triple(FailoverFailureKind.HTTP_STATUS, 525, true),
        Triple(FailoverFailureKind.HTTP_STATUS, 530, true),
        Triple(FailoverFailureKind.HTTP_STATUS, 404, true),
        Triple(FailoverFailureKind.HTTP_STATUS, 401, false),
        Triple(FailoverFailureKind.HTTP_STATUS, 403, false),
        Triple(FailoverFailureKind.HTTP_STATUS, 456, false),
        Triple(FailoverFailureKind.HTTP_STATUS, 400, false),
        Triple(FailoverFailureKind.HTTP_STATUS, 429, false),
        Triple(FailoverFailureKind.HTTP_STATUS, 302, false),
        Triple(FailoverFailureKind.HTTP_STATUS, null, false),
        Triple(FailoverFailureKind.AUTH_REJECTED, null, false),
        Triple(FailoverFailureKind.INVALID_RESPONSE, null, true),
        Triple(FailoverFailureKind.CANCELLED, null, false),
        Triple(FailoverFailureKind.OTHER, null, false),
    )
}

/** Step 0.3 validation golden cases — mirrored 1:1 in nuvio-web `backupServers.golden.json`. */
object BackupServerGolden {

    data class Case(
        val name: String,
        val sourceType: String,
        val main: String,
        val entries: List<String>,
        val expectedUrls: List<String>,
        /** (row index, problem name) — problem names are BackupServerValidation.Problem entries. */
        val expectedProblems: List<Pair<Int, String>> = emptyList(),
    )

    val cases: List<Case> = listOf(
        Case("xtream host:port gets http", "xtream", "http://a.com:8080", listOf("b.com:8080"), listOf("http://b.com:8080")),
        Case("xtream trims, lowercases, drops default port and slash", "xtream", "http://a.com:8080",
            listOf("  HTTPS://B.Example.com:443/  "), listOf("https://b.example.com")),
        Case("xtream keeps only the origin", "xtream", "http://a.com", listOf("http://b.com/player_api.php?username=x"), listOf("http://b.com")),
        Case("non-http scheme rejected", "xtream", "http://a.com", listOf("ftp://b.com"), emptyList(), listOf(0 to "NOT_HTTP")),
        Case("duplicate of main (case + scheme added)", "xtream", "http://a.com:8080", listOf("A.COM:8080"), emptyList(),
            listOf(0 to "DUPLICATE_OF_MAIN")),
        Case("duplicate of main (default port + slash)", "xtream", "http://a.com", listOf("http://a.com:80/"), emptyList(),
            listOf(0 to "DUPLICATE_OF_MAIN")),
        Case("duplicate of an earlier row", "xtream", "http://a.com", listOf("http://b.com", "B.com"), listOf("http://b.com"),
            listOf(1 to "DUPLICATE")),
        Case("scheme differs = different server", "xtream", "http://a.com", listOf("https://b.com", "http://b.com"),
            listOf("https://b.com", "http://b.com")),
        Case("blank rows ignored", "xtream", "http://a.com", listOf("", "   ", "http://b.com"), listOf("http://b.com")),
        Case("at most five", "xtream", "http://a.com",
            listOf("b1.com", "b2.com", "b3.com", "b4.com", "b5.com", "b6.com"),
            listOf("http://b1.com", "http://b2.com", "http://b3.com", "http://b4.com", "http://b5.com"),
            listOf(5 to "TOO_MANY")),
        Case("invalid: no host", "xtream", "http://a.com", listOf("http://"), emptyList(), listOf(0 to "INVALID_URL")),
        Case("invalid: space in host", "xtream", "http://a.com", listOf("http://bad host"), emptyList(), listOf(0 to "INVALID_URL")),
        Case("invalid: port out of range", "xtream", "http://a.com", listOf("http://b.com:99999"), emptyList(), listOf(0 to "INVALID_URL")),
        Case("m3u keeps path and query", "m3u_url", "http://a.com/list.m3u",
            listOf("http://cdn.b.com/list.m3u?token=1"), listOf("http://cdn.b.com/list.m3u?token=1")),
        Case("m3u gets http", "m3u_url", "http://a.com/list.m3u", listOf("cdn.b.com/list.m3u"), listOf("http://cdn.b.com/list.m3u")),
        Case("m3u duplicate of main (host case, default port, trailing slash)", "m3u_url", "http://a.com/list.m3u",
            listOf("HTTP://A.COM:80/list.m3u/"), emptyList(), listOf(0 to "DUPLICATE_OF_MAIN")),
        Case("m3u other path on the same host is a different playlist", "m3u_url", "http://a.com/list.m3u",
            listOf("http://a.com/other.m3u"), listOf("http://a.com/other.m3u")),
        Case("m3u path is case-sensitive", "m3u_url", "http://a.com/list.m3u",
            listOf("http://a.com/LIST.m3u"), listOf("http://a.com/LIST.m3u")),
        Case("stalker keeps only the origin", "stalker", "http://portal.tv", listOf("http://portal2.tv:8080/c/"),
            listOf("http://portal2.tv:8080")),
        Case("m3u file has no backups", "m3u_file", "", listOf("http://b.com"), emptyList()),
    )
}
