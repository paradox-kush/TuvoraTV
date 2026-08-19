package com.nuvio.tv.arch

import com.lemonappdev.konsist.api.Konsist
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Architecture firewall for NuvioTV — ported from the KMP repos (2026-08-19). NuvioTV is a fork of
 * NuvioMedia/NuvioTV; this test keeps upstream-aligned code from naming a fork-only feature directly,
 * so upstream merges stay clean and fork features stay isolated behind a contract / Hilt-bound seam.
 *
 * Fork set = UPSTREAM-ABSENT packages, verified via `git cat-file -t origin/dev:<path>` (2026-08-19):
 * core/{iptv,epg,radar,rec,memory} + ui/screens/{iptv,radar,livetv}. core/analytics is fork-only but
 * EXEMPT (cross-cutting telemetry, thin diff — same call the KMP firewall made). Wiring layer = the
 * Hilt modules under core/di (they bind fork impls) + NuvioApplication.
 *
 * Baseline-and-ratchet: the pre-existing crossings are frozen in [ArchBaseline]; this test fails only
 * on NEW crossings, and the baseline only SHRINKS as fork references move behind a contract or a
 * Hilt-bound interface. Do not add entries to silence a rule; fix the crossing.
 */
class ArchitectureTest {

    private val files: List<Pair<String, String>> =
        Konsist.scopeFromProject().files
            .map { it.path to it.text }
            .filter { (p, _) -> "/app/src/main/" in p }

    // Fork side = upstream-absent paths, not a directory-naming convention.
    private val forkPaths = listOf(
        "/core/iptv/", "/core/epg/", "/core/radar/", "/core/rec/", "/core/memory/",
        "/core/analytics/", // cross-cutting fork telemetry — its files may reference fork features
        "/ui/screens/iptv/", "/ui/screens/radar/", "/ui/screens/livetv/",
    )
    private fun isForkFile(path: String) = forkPaths.any { path.contains(it) }

    // Composition root: the Hilt modules that bind fork impls, plus the Application entry point.
    private fun isWiringFile(path: String) =
        "/core/di/" in path || path.endsWith("/NuvioApplication.kt")

    // Fork-feature references. core.analytics is fork-only but DELIBERATELY EXEMPT (cross-cutting
    // telemetry), so it is not part of this pattern.
    private val forkRef = Regex(
        """\bcom\.nuvio\.tv\.core\.(iptv|epg|radar|rec|memory)\.""" +
            """|\bcom\.nuvio\.tv\.ui\.screens\.(iptv|radar|livetv)\.""",
    )

    // Strip block + WHOLE-LINE // comments only. A naive //.* eats the // in "https://…" literals and
    // silently disables enforcement for that line (an invisible false negative).
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)^\s*//.*$"""), "")

    private fun rel(path: String) = path.substringAfter("/app/src/main/java/")

    @Test
    fun `upstream-aligned code never references a fork-only feature directly`() {
        val violations = files
            .filter { (p, _) -> !isForkFile(p) && !isWiringFile(p) }
            .filter { (_, text) -> forkRef.containsMatchIn(stripComments(text)) }
            .map { (p, _) -> rel(p) }
            .filterNot { it in ArchBaseline.crossings }
            .sorted()
        assertTrue(
            "NEW firewall crossing(s) — reach the fork feature through a contract / Hilt-bound " +
                "interface, not a direct reference:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
