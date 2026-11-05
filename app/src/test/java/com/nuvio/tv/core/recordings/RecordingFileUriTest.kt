package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.RecordingFileUri
import org.junit.Test
import org.junit.Assert.assertEquals

class RecordingFileUriTest {
    @Test
    fun `a plain absolute path gets the file scheme`() {
        assertEquals("file:///data/user/0/app/files/recordings/News-1.ts", RecordingFileUri.fromPath("/data/user/0/app/files/recordings/News-1.ts"))
    }

    @Test
    fun `spaces and non-ascii letters are percent-encoded as utf-8`() {
        assertEquals("file:///r/Les%20Mis%C3%A9rables-2.ts", RecordingFileUri.fromPath("/r/Les Misérables-2.ts"))
    }

    @Test
    fun `characters that would end a path or start a query are encoded`() {
        assertEquals("file:///r/a%3Fb%23c%25d.ts", RecordingFileUri.fromPath("/r/a?b#c%d.ts"))
    }

    @Test
    fun `an already file-scheme path is not doubled`() {
        assertEquals("file:///r/x.ts", RecordingFileUri.fromPath("file:///r/x.ts"))
    }
}
