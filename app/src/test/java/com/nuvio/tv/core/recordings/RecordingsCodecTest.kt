package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.api.CaptureMode
import com.nuvio.tv.core.recordings.api.RecordingCatchUp
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.internal.FireEntry
import com.nuvio.tv.core.recordings.internal.FireLedgerState
import com.nuvio.tv.core.recordings.internal.FireOutcome
import com.nuvio.tv.core.recordings.internal.FireState
import com.nuvio.tv.core.recordings.internal.RecordingFireLedger
import com.nuvio.tv.core.recordings.internal.RecordingsCodec
import com.nuvio.tv.core.recordings.internal.RecordingsDocument
import com.nuvio.tv.core.recordings.internal.StoredRow
import com.nuvio.tv.core.recordings.internal.reconcile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class RecordingsCodecTest {

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun fullDoc() = RecordingsDocument(
        schedules = listOf(
            StoredRow.of(schedule("s1", CaptureMode.CATCH_UP, ch = channel(catchUp = xtream(5)))),
            StoredRow.of(schedule("s2", CaptureMode.LIVE, ch = channel(catchUp = RecordingCatchUp.M3u("append", "?x={utc}", 2, 1000)))),
        ),
        series = listOf(StoredRow.of(rule("r1", "Show", keepLast = 3))),
        recordings = listOf(StoredRow.of(recording("rec1", RecordingStatus.FAILED).copy(problem = RecordingProblem.NO_SPACE))),
    )

    @Test
    fun `a full document round trips through encode and decode`() {
        val doc = fullDoc()
        val back = assertNotNull(RecordingsCodec.decode(RecordingsCodec.encode(doc)))
        assertEquals("schedules", doc.scheduleValues, back.scheduleValues)
        assertEquals("series", doc.seriesValues, back.seriesValues)
        assertEquals("recordings", doc.recordingValues, back.recordingValues)
        assertEquals("version", 2, back.schemaVersion)
    }

    @Test
    fun `the optional need and free bytes of a space failure round trip and stay additive`() {
        val failed = recording("rec1", RecordingStatus.FAILED).copy(
            problem = RecordingProblem.NO_SPACE, problemNeedBytes = 1_100_000_000L, problemFreeBytes = 900_000_000L,
        )
        val doc = RecordingsDocument(recordings = listOf(StoredRow.of(failed)))
        val text = RecordingsCodec.encode(doc)
        val back = assertNotNull(RecordingsCodec.decode(text)).recordingValues.single()
        assertEquals(1_100_000_000L, back.problemNeedBytes)
        assertEquals(900_000_000L, back.problemFreeBytes)
        assertEquals("no schema bump: the fields are additive", 2, parse(text).getValue("schemaVersion").jsonPrimitive.int)

        // A row written before the fields existed decodes with nulls and is not lost.
        val plain = RecordingsCodec.encode(RecordingsDocument(recordings = listOf(StoredRow.of(recording("old")))))
        val legacy = plain.replace(",\"problemNeedBytes\":null,\"problemFreeBytes\":null", "")
        assertTrue("the fixture really is the old shape", "problemNeedBytes" !in legacy)
        val old = assertNotNull(RecordingsCodec.decode(legacy)).recordingValues.single()
        assertNull(old.problemNeedBytes)
        assertNull(old.problemFreeBytes)
    }

    @Test
    fun `the encoded document names its schemaVersion and the three lists`() {
        val obj = parse(RecordingsCodec.encode(fullDoc()))
        assertEquals(2, obj.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(2, obj.getValue("schedules").jsonArray.size)
        assertEquals(1, obj.getValue("series").jsonArray.size)
        assertEquals(1, obj.getValue("recordings").jsonArray.size)
    }

    @Test
    fun `a catch-up kind is a discriminator and does not collide with the M3U type field`() {
        val text = RecordingsCodec.encode(fullDoc())
        val ch = parse(text).getValue("schedules").jsonArray[1].jsonObject.getValue("channel").jsonObject
        val cu = ch.getValue("catchUp").jsonObject
        assertEquals("m3u", cu.getValue("kind").jsonPrimitive.content)
        assertEquals("append", cu.getValue("type").jsonPrimitive.content)
        val back = assertNotNull(RecordingsCodec.decode(text))
        assertEquals(RecordingCatchUp.M3u("append", "?x={utc}", 2, 1000), back.scheduleValues[1].channel.catchUp)
    }

    @Test
    fun `an unknown field on a row survives decode then encode`() {
        val text = RecordingsCodec.encode(fullDoc())
        val withExtra = parse(text).let { o ->
            val rows = o.getValue("recordings").jsonArray.map { r ->
                JsonObject(r.jsonObject + ("futureField" to JsonPrimitive("kept")))
            }
            JsonObject(o + ("recordings" to JsonArray(rows)))
        }.toString()
        val doc = assertNotNull(RecordingsCodec.decode(withExtra))
        val again = parse(RecordingsCodec.encode(doc))
        assertEquals("kept", again.getValue("recordings").jsonArray[0].jsonObject.getValue("futureField").jsonPrimitive.content)
    }

    @Test
    fun `an unknown field inside the channel survives an update of the row`() {
        val text = RecordingsCodec.encode(fullDoc())
        val o = parse(text)
        val sched = o.getValue("schedules").jsonArray[0].jsonObject
        val ch = JsonObject(sched.getValue("channel").jsonObject + ("regionHint" to JsonPrimitive("eu")))
        val patched = JsonObject(o + ("schedules" to JsonArray(listOf(JsonObject(sched + ("channel" to ch))))))
        val doc = assertNotNull(RecordingsCodec.decode(patched.toString()))
        // An older app edits the schedule and writes the file back.
        val updated = doc.copy(schedules = doc.schedules.map { it.withValue(it.value!!.copy(attempts = 2)) })
        val out = parse(RecordingsCodec.encode(updated)).getValue("schedules").jsonArray[0].jsonObject
        assertEquals("the edit lands", 2, out.getValue("attempts").jsonPrimitive.int)
        assertEquals("the unknown channel field stays", "eu", out.getValue("channel").jsonObject.getValue("regionHint").jsonPrimitive.content)
    }

    @Test
    fun `a removed header does not come back from the raw row`() {
        val ch = channel().copy(headers = mapOf("User-Agent" to "x", "Referer" to "y"))
        val doc = RecordingsDocument(schedules = listOf(StoredRow.of(schedule("s1", ch = ch))))
        val loaded = assertNotNull(RecordingsCodec.decode(RecordingsCodec.encode(doc)))
        val edited = loaded.copy(schedules = loaded.schedules.map { it.withValue(it.value!!.copy(channel = ch.copy(headers = mapOf("User-Agent" to "x")))) })
        val back = assertNotNull(RecordingsCodec.decode(RecordingsCodec.encode(edited)))
        assertEquals(mapOf("User-Agent" to "x"), back.scheduleValues[0].channel.headers)
    }

    @Test
    fun `an unknown top-level key survives`() {
        val text = """{"schemaVersion":1,"schedules":[],"series":[],"recordings":[],"deviceNote":{"a":1}}"""
        val doc = assertNotNull(RecordingsCodec.decode(text))
        val out = parse(RecordingsCodec.encode(doc))
        assertEquals(1, out.getValue("deviceNote").jsonObject.getValue("a").jsonPrimitive.int)
    }

    @Test
    fun `a higher schemaVersion is read and kept and no row is dropped`() {
        val v3 = parse(RecordingsCodec.encode(fullDoc())).let { JsonObject(it + ("schemaVersion" to JsonPrimitive(3))) }.toString()
        val doc = assertNotNull(RecordingsCodec.decode(v3))
        assertEquals(3, doc.schemaVersion)
        assertEquals(2, doc.schedules.size)
        assertEquals(1, doc.recordings.size)
        val out = parse(RecordingsCodec.encode(doc))
        assertEquals("an older writer must not downgrade the file", 3, out.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(2, out.getValue("schedules").jsonArray.size)
    }

    @Test
    fun `a missing schemaVersion is legacy 1 and is written back as the current version`() {
        val doc = assertNotNull(RecordingsCodec.decode("""{"schedules":[],"series":[],"recordings":[]}"""))
        assertEquals("what the file said", 1, doc.loadedVersion)
        assertEquals(2, parse(RecordingsCodec.encode(doc)).getValue("schemaVersion").jsonPrimitive.int)
    }

    @Test
    fun `the fire ledger lives inside the document and round trips`() {
        val entry = FireEntry("s1", FireState.RUNNING, "s1#4", 7L)
        val doc = fullDoc().withLedger(FireLedgerState(listOf(entry), nextSeq = 5))
        val back = assertNotNull(RecordingsCodec.decode(RecordingsCodec.encode(doc)))
        assertEquals(FireLedgerState(listOf(entry), 5), back.ledgerState)
    }

    @Test
    fun `unknown ledger fields survive a rewrite`() {
        val text = """{"schemaVersion":2,"schedules":[],"series":[],"recordings":[],
            "ledger":{"nextSeq":3,"futureLedgerKey":{"a":1},
              "entries":[{"scheduleId":"s1","state":"RUNNING","token":"s1#2","claimedAtElapsedMs":9,"futureEntryField":"keep"}]}}"""
        val doc = assertNotNull(RecordingsCodec.decode(text))
        val rewritten = doc.withLedger(RecordingFireLedger(doc.ledgerState).also { it.release("s1#2", FireOutcome.DONE) }.snapshot())
        val ledger = parse(RecordingsCodec.encode(rewritten)).getValue("ledger").jsonObject
        assertEquals("ledger-level field", 1, ledger.getValue("futureLedgerKey").jsonObject.getValue("a").jsonPrimitive.int)
        val entry = ledger.getValue("entries").jsonArray[0].jsonObject
        assertEquals("entry-level field", "keep", entry.getValue("futureEntryField").jsonPrimitive.content)
        assertEquals("and the update still lands", "DONE", entry.getValue("state").jsonPrimitive.content)
        assertEquals(3L, ledger.getValue("nextSeq").jsonPrimitive.long)
    }


    @Test
    fun `a row that does not decode is kept verbatim`() {
        val bad = """{"id":"x","status":"SOMETHING_NEW","futureOnly":true}"""
        val text = """{"schemaVersion":1,"recordings":[$bad]}"""
        val doc = assertNotNull(RecordingsCodec.decode(text))
        assertEquals("the row is kept", 1, doc.recordings.size)
        assertNull("but is not typed", doc.recordings[0].value)
        assertTrue(doc.recordingValues.isEmpty())
        val out = parse(RecordingsCodec.encode(doc)).getValue("recordings").jsonArray
        assertEquals("and re-emitted unchanged", parse(bad), out[0].jsonObject)
    }

    @Test
    fun `a row with a catch-up kind from a newer app is kept raw`() {
        val doc = fullDoc()
        val text = parse(RecordingsCodec.encode(doc)).let { o ->
            val s = o.getValue("schedules").jsonArray[0].jsonObject
            val ch = s.getValue("channel").jsonObject
            val cu = JsonObject(mapOf("kind" to JsonPrimitive("stalker"), "days" to JsonPrimitive(3)))
            val s2 = JsonObject(s + ("channel" to JsonObject(ch + ("catchUp" to cu))))
            JsonObject(o + ("schedules" to JsonArray(listOf(s2)))).toString()
        }
        val back = assertNotNull(RecordingsCodec.decode(text))
        assertEquals(1, back.schedules.size)
        assertNull(back.schedules[0].value)
        val out = parse(RecordingsCodec.encode(back)).getValue("schedules").jsonArray[0].jsonObject
        assertEquals("stalker", out.getValue("channel").jsonObject.getValue("catchUp").jsonObject.getValue("kind").jsonPrimitive.content)
    }

    @Test
    fun `a row that is not even an object is kept`() {
        val doc = assertNotNull(RecordingsCodec.decode("""{"schemaVersion":1,"series":["oops",7]}"""))
        assertEquals(2, doc.series.size)
        val out = parse(RecordingsCodec.encode(doc)).getValue("series").jsonArray
        assertEquals(listOf(JsonPrimitive("oops"), JsonPrimitive(7)), out.toList())
    }

    @Test
    fun `blank or missing text is an empty document`() {
        for (t in listOf(null, "", "   ")) {
            val d = assertNotNull(RecordingsCodec.decode(t), "text=$t")
            assertTrue(d.schedules.isEmpty() && d.series.isEmpty() && d.recordings.isEmpty())
        }
    }

    @Test
    fun `text that is not a JSON object is corrupt and not an empty document`() {
        assertNull("garbage", RecordingsCodec.decode("{not json"))
        assertNull("an array", RecordingsCodec.decode("[1,2]"))
    }

    @Test
    fun `reconcile keeps raw for known ids adds new rows and keeps undecodable rows`() {
        val raw = parse("""{"id":"s1","futureField":1}""")
        val kept = StoredRow(schedule("s1"), raw)
        val opaque = StoredRow<com.nuvio.tv.core.recordings.api.RecordingSchedule>(null, parse("""{"id":"zzz"}"""))
        val rows = listOf(kept, opaque)
        val rebuilt = rows.reconcile(listOf(schedule("s1", title = "Renamed"), schedule("s2"))) { it.id }
        assertEquals(3, rebuilt.size)
        assertEquals("Renamed", rebuilt[0].value?.title)
        assertEquals("unknown fields stay with their row", raw, rebuilt[0].raw)
        assertEquals("s2", rebuilt[1].value?.id)
        assertNull("undecodable row stays at the end", rebuilt[2].value)
    }

    @Test
    fun `a schedule removed by the app is removed from the file`() {
        val rows = listOf(StoredRow.of(schedule("s1")), StoredRow.of(schedule("s2")))
        val rebuilt = rows.reconcile(listOf(schedule("s2"))) { it.id }
        assertEquals(listOf("s2"), rebuilt.mapNotNull { it.value?.id })
    }
}
