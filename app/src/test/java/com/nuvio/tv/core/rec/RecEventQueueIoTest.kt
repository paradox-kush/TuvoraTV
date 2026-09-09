package com.nuvio.tv.core.rec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class RecEventQueueIoTest {
    @Test
    fun `reads content within the limit`() {
        val data = "hello world".toByteArray(Charsets.UTF_8)
        assertEquals("hello world", readBoundedUtf8(ByteArrayInputStream(data), 1024))
    }

    @Test
    fun `returns null when the stream exceeds the byte limit`() {
        val data = ByteArray(2048) { 'x'.code.toByte() }
        assertNull("over the byte cap, enforced while consuming", readBoundedUtf8(ByteArrayInputStream(data), 1024))
    }

    @Test
    fun `content exactly at the limit is kept`() {
        val data = ByteArray(1024) { 'a'.code.toByte() }
        assertEquals(1024, readBoundedUtf8(ByteArrayInputStream(data), 1024)?.length)
    }

    @Test
    fun `empty stream yields empty string`() {
        assertEquals("", readBoundedUtf8(ByteArrayInputStream(ByteArray(0)), 16))
    }
}
