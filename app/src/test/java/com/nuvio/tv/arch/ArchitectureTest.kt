package com.nuvio.tv.arch

import com.lemonappdev.konsist.api.Konsist
import java.io.File
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

    private val userDirectory =
        requireNotNull(System.getProperty("user.dir")) { "JVM user.dir is unavailable" }

    private val projectRoot: File =
        generateSequence(File(userDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV project root from user.dir=$userDirectory")

    private val productionSourceRoot = File(projectRoot, "app/src/main").canonicalFile.toPath()

    private val files: List<Pair<String, String>> =
        Konsist.scopeFromDirectory("app/src/main").files
            .map { File(it.path).canonicalFile to it.text }
            // The scope is the canonical source root for this Gradle invocation. It therefore
            // includes this checkout when it is under .../wt/<lane>, without scanning sibling or
            // nested worktrees at all.
            .map { (file, text) -> file.path to text }

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

    // Clean-slate playback is deliberately being built beside the frozen legacy player. Keep these
    // predicates path-based so every rule is useful before the first clean source file exists and so
    // a package cannot evade a boundary by choosing a convenient class name.
    private fun isCleanPlaybackFile(path: String) =
        rel(path).startsWith("com/nuvio/tv/playback/")

    private fun isCleanPlaybackPackage(path: String, packageName: String) =
        rel(path).startsWith("com/nuvio/tv/playback/$packageName/")

    private fun isLegacyPlaybackOrchestration(path: String): Boolean {
        val relative = rel(path)
        return relative.startsWith("com/nuvio/tv/core/player/") ||
            relative.startsWith("com/nuvio/tv/player/") ||
            relative.startsWith("com/nuvio/tv/ui/screens/player/") ||
            relative.startsWith("com/nuvio/tv/ui/screens/iptv/player/") ||
            relative.endsWith("/XtreamLiveGuideScreen.kt") ||
            relative.endsWith("/XtreamLiveGuideViewModel.kt")
    }

    private fun imports(text: String): List<String> =
        Regex("""(?m)^\s*import\s+([^\s]+)""")
            .findAll(stripComments(text))
            .map { it.groupValues[1] }
            .toList()

    private fun assertProductionFilesCollected() {
        assertTrue(
            "Architecture scan found no production files under $productionSourceRoot; " +
                "the firewall must fail closed rather than silently pass.",
            files.isNotEmpty(),
        )
    }

    private fun assertCleanPlaybackFilesCollected() {
        assertProductionFilesCollected()
        assertTrue(
            "Architecture scan found no clean playback files under " +
                "$productionSourceRoot/java/com/nuvio/tv/playback; the clean firewall must fail closed.",
            files.any { (path, _) -> isCleanPlaybackFile(path) },
        )
    }

    @Test
    fun `upstream-aligned code never references a fork-only feature directly`() {
        assertProductionFilesCollected()
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

    // ── mpv-engine seam (research/tv-player-mpv-engine-ownership.md, Part A, D5) ──────────────────

    /**
     * Rule A — libmpv containment. Only the frozen legacy mpv engine shell/package and the clean
     * playback.mpv adapter may name the `is.xyz.mpv` bindings / BaseMPVView, so libmpv calls can
     * never be sprayed into controllers, UI, or the engine-neutral clean core.
     * Matches the backtick-quoted `is` package reference — the plain string `"is.xyz.mpv.MPVActivity.result"`
     * (an Intent action for the *external* mpv-android app in ExternalPlayerResultContract) is not a
     * binding use and is correctly ignored.
     */
    @Test
    fun `only mpv adapter boundaries name libmpv`() {
        assertProductionFilesCollected()
        // The shell (declares the SurfaceView) + the fork-owned engine package (extracted internals:
        // property shadow, and later the ctl queue / lifecycle) are the only places libmpv is named.
        fun isEnginePackage(rel: String) =
            rel == "com/nuvio/tv/ui/screens/player/NuvioMpvSurfaceView.kt" ||
                rel.startsWith("com/nuvio/tv/player/mpv/") ||
                rel.startsWith("com/nuvio/tv/playback/mpv/")
        val libmpvRef = Regex("`is`\\.xyz\\.mpv|\\bBaseMPVView\\b")
        val violations = files
            .filter { (p, _) -> !isEnginePackage(rel(p)) }
            .filter { (_, text) -> libmpvRef.containsMatchIn(stripComments(text)) }
            .map { (p, _) -> rel(p) }
            .sorted()
        assertTrue(
            "Only the legacy mpv shell/package and clean playback.mpv adapter may name libmpv " +
                "(`is.xyz.mpv` / BaseMPVView):\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * Rule B — concrete-type containment. Controllers must talk to the mpv engine through the
     * [com.nuvio.tv.ui.screens.player.MpvSurface] contract, never the concrete NuvioMpvSurfaceView.
     * Only the shell (which declares it) and PlayerScreen (the one AndroidView construction site, which
     * uses it purely as a View) may name it.
     */
    @Test
    fun `only the shell and PlayerScreen name the concrete NuvioMpvSurfaceView`() {
        assertProductionFilesCollected()
        val allowed = setOf(
            "com/nuvio/tv/ui/screens/player/NuvioMpvSurfaceView.kt",
            "com/nuvio/tv/ui/screens/player/PlayerScreen.kt",
        )
        val concreteRef = Regex("\\bNuvioMpvSurfaceView\\b")
        val violations = files
            .filter { (p, _) -> rel(p) !in allowed }
            .filter { (_, text) -> concreteRef.containsMatchIn(stripComments(text)) }
            .map { (p, _) -> rel(p) }
            .sorted()
        assertTrue(
            "Talk to the mpv engine through MpvSurface, not the concrete NuvioMpvSurfaceView:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // -- clean-slate playback boundary -------------------------------------------------------------

    /**
     * The playback core is a pure Kotlin decision engine. Platform facts, persistence, provider
     * models, telemetry, dependency injection, and UI are all reached through ports defined by the
     * core; none of their implementation types may leak back into it.
     */
    @Test
    fun `clean playback core has only pure engine-neutral imports`() {
        assertCleanPlaybackFilesCollected()
        val forbiddenImport = Regex(
            """^(?:android(?:x)?\.|org\.jetbrains\.compose\.|com\.posthog\.|dagger\.|""" +
                """javax\.inject\.|jakarta\.inject\.|com\.nuvio\.tv\.ui\.|""" +
                """com\.nuvio\.tv\.data\.|com\.nuvio\.tv\.domain\.repository\.|""" +
                """com\.nuvio\.tv\.core\.(?:analytics|iptv)\.|""" +
                """com\.nuvio\.tv\.(?:core\.player|player)\.|.*\.(?:provider|storage)\.)""",
        )
        val violations = files
            .filter { (path, _) -> isCleanPlaybackPackage(path, "core") }
            .mapNotNull { (path, text) ->
                imports(text).filter(forbiddenImport::containsMatchIn).takeIf(List<String>::isNotEmpty)
                    ?.let { rel(path) + " -> " + it.joinToString() }
            }
            .sorted()
        assertTrue(
            "playback.core must stay pure Kotlin and engine-neutral; depend on core ports instead:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** Direct SDK use is legal only inside the matching adapter package. */
    @Test
    fun `clean playback engine APIs stay inside their adapters`() {
        assertCleanPlaybackFilesCollected()
        val media3Ref = Regex("""\bandroidx\.media3\.""")
        val libmpvRef = Regex("""(?:`is`|is)\.xyz\.mpv\.|\bBaseMPVView\b""")
        val violations = files
            .filter { (path, _) -> isCleanPlaybackFile(path) }
            .flatMap { (path, text) ->
                val source = stripComments(text)
                buildList {
                    if (!isCleanPlaybackPackage(path, "media3") && media3Ref.containsMatchIn(source)) {
                        add(rel(path) + " -> Media3 API")
                    }
                    if (!isCleanPlaybackPackage(path, "mpv") && libmpvRef.containsMatchIn(source)) {
                        add(rel(path) + " -> libmpv API")
                    }
                }
            }
            .sorted()
        assertTrue(
            "Only playback.media3 may use Media3 APIs and only playback.mpv may use libmpv APIs:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * Parallel construction is isolation, not gradual delegation: the new implementation and the
     * frozen oracle cannot import one another in either direction.
     */
    @Test
    fun `clean and legacy playback orchestration never depend on each other`() {
        assertCleanPlaybackFilesCollected()
        val legacyRef = Regex(
            """\bcom\.nuvio\.tv\.(?:core\.player|player|ui\.screens\.player|""" +
                """ui\.screens\.iptv\.player)\.""",
        )
        val cleanRef = Regex("""\bcom\.nuvio\.tv\.playback\.""")
        val violations = files.flatMap { (path, text) ->
            val source = stripComments(text)
            buildList {
                if (isCleanPlaybackFile(path) && legacyRef.containsMatchIn(source)) {
                    add(rel(path) + " -> legacy playback")
                }
                if (isLegacyPlaybackOrchestration(path) && cleanRef.containsMatchIn(source)) {
                    add(rel(path) + " -> clean playback")
                }
            }
        }.sorted()
        assertTrue(
            "Clean playback and frozen legacy playback must remain isolated until atomic cutover:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** Device identity may describe a verified quirk; it may never become an inline policy branch. */
    @Test
    fun `clean device identity checks live only in Android capability and quirk providers`() {
        assertCleanPlaybackFilesCollected()
        val deviceIdentityRef = Regex(
            """\b(?:android\.os\.)?Build\.(?:MODEL|MANUFACTURER|BRAND|DEVICE|HARDWARE|PRODUCT|""" +
                """SOC_MODEL|SOC_MANUFACTURER)\b""",
        )
        val violations = files
            .filter { (path, _) ->
                isCleanPlaybackFile(path) && !isCleanPlaybackPackage(path, "android")
            }
            .filter { (_, text) -> deviceIdentityRef.containsMatchIn(stripComments(text)) }
            .map { (path, _) -> rel(path) }
            .sorted()
        assertTrue(
            "Device model/manufacturer checks belong in playback.android capability/quirk providers:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * Settings persist intent and UI sends session commands. Neither layer may construct an adapter
     * or invoke an engine-shaped mutable API directly.
     */
    @Test
    fun `clean settings and UI never construct or configure engines`() {
        assertCleanPlaybackFilesCollected()
        val adapterRef = Regex("""\bcom\.nuvio\.tv\.playback\.(?:media3|mpv)\.""")
        val engineConstruction = Regex(
            """\b(?:ExoPlayer\s*\.\s*Builder|SimpleExoPlayer|Media3Engine|LibmpvEngine|MpvEngine)\s*\(""",
        )
        val directEngineCall = Regex(
            """\b(?:engine|player|exoPlayer|mpv)\s*\.\s*(?:set[A-Z]\w*|prepare|build|""" +
                """release|stop|play|pause)\s*\(""",
        )
        val violations = files
            .filter { (path, _) ->
                isCleanPlaybackPackage(path, "settings") || isCleanPlaybackPackage(path, "ui")
            }
            .mapNotNull { (path, text) ->
                val source = stripComments(text)
                buildList {
                    if (adapterRef.containsMatchIn(source)) add("adapter dependency")
                    if (engineConstruction.containsMatchIn(source)) add("engine construction")
                    if (directEngineCall.containsMatchIn(source)) add("direct engine mutation")
                }.takeIf(List<String>::isNotEmpty)?.let { rel(path) + " -> " + it.joinToString() }
            }
            .sorted()
        assertTrue(
            "playback.settings/ui must use preferences and PlaybackSessionController, not engines:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
