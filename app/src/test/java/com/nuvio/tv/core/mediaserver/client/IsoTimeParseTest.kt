package com.nuvio.tv.core.mediaserver.client

import com.nuvio.tv.core.mediaserver.client.mediabrowser.IsoTime
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class IsoTimeParseTest {
    @Test
    fun parsesTheFormsTheServersWrite() {
        assertEquals(0L, IsoTime.parse("1970-01-01T00:00:00Z"))
        assertEquals("a 7-digit .NET fraction keeps milliseconds", 1_759_782_669_201L, IsoTime.parse("2025-10-06T20:31:09.2016699Z"))
        assertEquals(1_759_782_669_000L, IsoTime.parse("2025-10-06T20:31:09Z"))
        assertEquals("an offset is applied", 1_759_782_669_000L, IsoTime.parse("2025-10-06T22:31:09+02:00"))
        assertEquals("no offset = UTC", 1_759_782_669_000L, IsoTime.parse("2025-10-06T20:31:09"))
    }

    @Test
    fun isTheInverseOfFormat() {
        for (ms in listOf(0L, 86_399_000L, 951_782_400_000L, 1_759_782_669_000L, 4_102_444_799_000L)) {
            assertEquals("round trip of $ms", ms, IsoTime.parse(IsoTime.format(ms)))
        }
    }

    @Test
    fun garbageIsNullNotACrash() {
        assertNull(IsoTime.parse(null))
        assertNull(IsoTime.parse(""))
        assertNull(IsoTime.parse("yesterday"))
        assertNull(IsoTime.parse("2025-13-40T25:61:61Z"))
    }
}
