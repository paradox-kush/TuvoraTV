package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.internal.AndroidRecordingFiles
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidRecordingFilesTest {
    private val root = Files.createTempDirectory("rec-files").toFile()
    private val files = AndroidRecordingFiles(File(root, "state"), File(root, "rec"))

    @After
    fun tearDown() { root.deleteRecursively() }

    @Test
    fun `a write replaces the old text whole and leaves no temporary file`() = runBlocking {
        assertNull(files.read("a.json"))
        files.write("a.json", "one")
        files.write("a.json", "two")
        assertEquals("two", files.read("a.json"))
        assertTrue(File(root, "state").list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun `keepAside moves the file and never deletes it`() = runBlocking {
        files.write("a.json", "broken")
        files.keepAside("a.json")
        assertNull(files.read("a.json"))
        val kept = File(root, "state").listFiles()!!.single()
        assertTrue(kept.name.startsWith("a.json.corrupt-"))
        assertEquals("broken", kept.readText())
    }

    @Test
    fun `only plain file names are accepted for recordings`() {
        assertTrue(files.pathFor("show.ts").endsWith("/rec/show.ts"))
        val failed = runCatching { files.pathFor("../x") }.isFailure
        assertTrue(failed)
    }
}
