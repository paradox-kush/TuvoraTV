package com.nuvio.tv.core.startup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cold-start ANR guards (field: ~170 users in 14 days, foreground and "Timed out while trying to
 * bind" / "No response to onStartJob" — JobService and receivers run Application.onCreate first).
 * Source scans, so a regression fails the build instead of showing up in the ANR graph.
 */
class StartupMainThreadGuardTest {

    @Test
    fun `NuvioApplication never blocks onCreate on a coroutine`() {
        assertFalse(
            "NuvioApplication.kt must not use runBlocking (it runs on Main for every process start)",
            mainSource("NuvioApplication.kt").contains("runBlocking"),
        )
    }

    @Test
    fun `SentryInitializer never blocks its caller on a coroutine`() {
        assertFalse(
            "SentryInitializer.kt must not use runBlocking (it is called from Application.onCreate)",
            mainSource("core/diagnostics/SentryInitializer.kt").contains("runBlocking"),
        )
    }

    /**
     * Building the Supabase client (Ktor plugin install, kotlin-reflect typeOf) cost ~8% of cold-start
     * main-thread CPU because @Singleton consumers took Postgrest/SupabaseClient/Auth/Storage/Realtime by
     * value, so merely constructing them during Application/Activity injection built the client.
     * Injected consumers must take dagger.Lazy<…> and resolve at use.
     */
    @Test
    fun `no injected class takes a Supabase plugin or client by value`() {
        val byValue = Regex("""^\s*(?:@\S+\s+)*private val \w+: (Postgrest|SupabaseClient|Auth|Storage|Realtime),?\s*$""")
        val offenders = mainRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("@Inject constructor") }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> byValue.matches(line) }
                    .map { (index, line) -> "${file.relativeTo(mainRoot())}:${index + 1}: ${line.trim()}" }
            }
            .toList()
        assertEquals("constructor parameters that build the Supabase client on injection", emptyList<String>(), offenders)
    }

    @Test
    fun `the scan actually sees the Supabase consumers`() {
        // Guards the guard: if the source root moved, the scan above would pass vacuously.
        assertTrue(
            "DeviceSessionRegistration.kt must be found under the scanned root",
            File(mainRoot(), "core/auth/DeviceSessionRegistration.kt").isFile,
        )
    }

    private fun mainRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(userDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV project root")
        return File(projectRoot, "app/src/main/java/com/nuvio/tv")
    }

    private fun mainSource(relative: String): String = File(mainRoot(), relative).readText()
}
