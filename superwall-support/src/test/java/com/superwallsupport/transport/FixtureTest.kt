package com.superwallsupport.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Decodes every person-side wire fixture from paywall-next
 * (`packages/inbox-contracts/fixtures`, synced by `scripts/sync-fixtures.sh`)
 * the way its manifest says a client must.
 */
class FixtureTest {
    companion object {
        fun text(path: String): String =
            FixtureTest::class.java.classLoader!!
                .getResource("support-fixtures/$path")!!
                .readText()

        fun load(path: String): JsonElement = parseJson(text(path)) ?: error("$path isn't JSON")

        fun entry(
            seq: Long,
            id: String,
            kind: String = "agent_message",
        ): JsonObject =
            buildJsonObject {
                put("seq", seq)
                put("id", id)
                put("conversationId", "conv_00000000000000000000000001")
                put("kind", kind)
                put("visibility", "public")
                put("author", buildJsonObject { put("type", "agent") })
                put("createdAt", "2026-11-05T10:00:00.000Z")
                put("text", "Entry $seq")
            }

        fun window(entries: List<JsonElement>): EntryWindow =
            EntryWindow(
                "conv_00000000000000000000000001",
                JsonArray(entries),
                (entries.lastOrNull() as? JsonObject)?.get("seq")?.intOrNull ?: 0,
                false,
            )

        val JsonElement?.str get() = this?.stringOrNull
        val JsonElement?.long get() = this?.intOrNull
    }

    @Test
    fun sourceIsRecorded() {
        val commit = (load("SOURCE.json") as JsonObject)["commit"].str
        assertEquals("SOURCE.json names the exact paywall-next commit", 40, commit?.length)
    }

    @Test
    fun everyWireFixture() {
        val manifest = load("manifest.json").jsonObject
        assertEquals(SupportWire.PROTOCOL, manifest["protocol"].str)
        val fixtures = manifest["fixtures"]!!.jsonArray.map { it.jsonObject }
        var checked = 0
        val skipped = mutableMapOf<String, Int>()
        for (fixture in fixtures) {
            val module = fixture["module"].str!!
            if (module != "wire") {
                skipped[module] = (skipped[module] ?: 0) + 1
                continue
            }
            check(fixture["path"].str!!, fixture["expect"]!!.jsonObject)
            checked += 1
        }
        assertTrue(checked >= 68)
        println("Checked $checked wire fixtures; skipped founder-side $skipped")
    }

    private fun check(
        path: String,
        expect: JsonObject,
    ) {
        val lenient = expect["lenient"].str
        when {
            lenient == "entry" -> {
                val decoded = WireEntry.decode(load(path))
                assertEquals("$path: $decoded", expect["outcome"].str, decoded.javaClass.simpleName)
            }
            lenient == "frame" -> {
                val decoded = WireFrameDecoder.decode(text(path))
                val outcome = if (decoded is DecodedFrame.Frame) "Frame" else "Ignored"
                assertEquals("$path: $decoded", expect["outcome"].str, outcome)
            }
            expect.containsKey("decode") -> {
                val valid = (expect["valid"] as JsonPrimitive).content.toBoolean()
                assertEquals("$path as ${expect["decode"].str}", valid, decodes(expect["decode"].str!!, path))
            }
            expect.containsKey("resume") -> checkResume(path)
            expect.containsKey("transcript") -> checkTranscript(path)
            else -> fail("No expectation for $path")
        }
    }

    private fun decodes(
        schema: String,
        path: String,
    ): Boolean {
        val raw = text(path)
        return try {
            when (schema) {
                "ClientFrame" -> return ClientFrame.decode(raw) != null
                "SessionStart" -> wireJson.decodeFromString(SessionStart.serializer(), raw)
                "SessionStarted" -> wireJson.decodeFromString(SessionStarted.serializer(), raw)
                "EntryWindow" -> wireJson.decodeFromString(EntryWindow.serializer(), raw)
                "SendMessage" -> wireJson.decodeFromString(SendMessage.serializer(), raw).validate()
                "SendAccepted" -> wireJson.decodeFromString(SendAccepted.serializer(), raw)
                "MarkRead" -> wireJson.decodeFromString(MarkRead.serializer(), raw)
                "UploadRequest" -> wireJson.decodeFromString(UploadRequest.serializer(), raw)
                "UploadIntent" -> wireJson.decodeFromString(UploadIntent.serializer(), raw)
                "UploadCompleted" -> wireJson.decodeFromString(UploadCompleted.serializer(), raw)
                "AttachmentUrl" -> wireJson.decodeFromString(AttachmentUrl.serializer(), raw)
                "CardActionRequest" -> wireJson.decodeFromString(CardActionRequest.serializer(), raw)
                "CardActionAccepted" -> wireJson.decodeFromString(CardActionAccepted.serializer(), raw)
                "ToolCallResultRequest" -> wireJson.decodeFromString(ToolCallResultRequest.serializer(), raw)
                "WireError" -> wireJson.decodeFromString(WireErrorBody.serializer(), raw)
                else -> fail("No decoder for $schema ($path)")
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The client half of each resume case (frames apply with no gap and no duplicate,
     * `ready` drops a held tail, a replayed entry seals it), with the server half
     * (`planResume`) re-derived so the fixture's meaning is pinned on this side too.
     */
    private fun checkResume(path: String) {
        val fixture = load(path).jsonObject
        val visible = fixture["visible"]!!.jsonArray.map { it.jsonObject["seq"].long!! to it.jsonObject["id"].str!! }
        val cursor = fixture["cursor"]!!.jsonObject
        val after = cursor["after"].long!!
        val afterId = cursor["afterId"].str
        val lastSeq = fixture["lastSeq"].long!!
        val maxBacklog = fixture["maxBacklog"].long!!
        val expected = fixture["expected"]!!.jsonObject
        val head = visible.lastOrNull()?.first ?: 0

        val boundary = visible.firstOrNull { it.first == after }
        val following = visible.filter { it.first > after }
        val (tag, reason) =
            when {
                after > lastSeq -> "Resync" to "beyond_head"
                after > 0 && (boundary == null || (afterId != null && boundary.second != afterId)) ->
                    "Resync" to "boundary_mismatch"
                following.size > maxBacklog -> "Resync" to "too_far_behind"
                else -> "Replay" to null
            }
        assertEquals(path, expected["_tag"].str, tag)
        assertEquals(path, expected["head"].long, head)
        if (tag == "Resync") {
            assertEquals(path, expected["reason"].str, reason)
            return
        }
        val seqs = expected["seqs"]!!.jsonArray.map { it.long!! }
        val prevs = expected["prevs"]!!.jsonArray.map { it.long!! }
        assertEquals(path, seqs, following.map { it.first })

        val log = ConversationLog()
        log.replace(window(visible.filter { it.first <= after }.map { entry(it.first, it.second) }))
        assertEquals(path, after, log.cursor)
        fixture["heldTail"]?.jsonObject?.let { held ->
            log.applyTail(held["messageId"].str!!, 0, held["text"].str!!)
            assertNotNull(path, log.tail)
        }
        log.ready()
        seqs.forEachIndexed { index, seq ->
            val raw = entry(seq, visible.first { it.first == seq }.second)
            assertEquals("$path seq $seq", EntryFrameOutcome.Accept, log.applyEntryFrame(seq, prevs[index], raw))
            assertEquals(path, EntryFrameOutcome.Duplicate, log.applyEntryFrame(seq, prevs[index], raw))
        }
        assertEquals(path, seqs.lastOrNull() ?: after, log.cursor)
        if (expected["tailAfter"].str == "dropped") assertNull(path, log.tail)
    }

    /** A cold client fed a transcript ends with the window's entries; so does one dropped at every seq. */
    private fun checkTranscript(path: String) {
        val fixture = load(path).jsonObject
        val frames = fixture["frames"]!!.jsonArray
        val window = wireJson.decodeFromJsonElement(EntryWindow.serializer(), fixture["window"]!!)

        val log = ConversationLog()
        frames.forEach { apply(it, log, path) }
        assertEquals(path, window.decodedEntries, log.entries)
        assertEquals(path, window.head, log.cursor)
        assertNull(path, log.tail)

        val entryFrames = frames.filter { it.jsonObject["type"].str == "entry" }
        for (dropAt in 0..entryFrames.size) {
            val resumed = ConversationLog()
            entryFrames.take(dropAt).forEach { apply(it, resumed, path) }
            val cursor = resumed.cursor
            resumed.ready()
            entryFrames.filter { it.jsonObject["seq"].long!! > cursor }.forEach { apply(it, resumed, path) }
            assertEquals("$path dropped at $dropAt", window.decodedEntries, resumed.entries)
        }
    }

    private fun apply(
        frame: JsonElement,
        log: ConversationLog,
        path: String,
    ) {
        val decoded = WireFrameDecoder.decode(frame) as? DecodedFrame.Frame ?: return fail("$path: frame didn't decode: $frame")
        when (val f = decoded.frame) {
            is ServerFrame.Ready -> log.ready()
            is ServerFrame.Entry -> assertEquals("$path seq ${f.seq}", EntryFrameOutcome.Accept, log.applyEntryFrame(f.seq, f.prev, f.entry))
            is ServerFrame.Tail -> log.applyTail(f.messageId, f.offset, f.text)
            is ServerFrame.TailCancel -> log.cancelTail(f.messageId)
            is ServerFrame.Status -> log.setStatus(f.state, f.etaSeconds)
            else -> Unit
        }
    }

    @Test
    fun undecodableEntryInAFrameStillCarriesItsSeqAndText() {
        val decoded = WireFrameDecoder.decode(text("wire/frames/server/entry-undecodable.json")) as DecodedFrame.Frame
        val frame = decoded.frame as ServerFrame.Entry
        val entry = WireEntry.decode(frame.entry)
        assertTrue(entry is WireEntry.Failed)
        assertEquals("Reply 'yes'.", entry.fallbackText)
        val log = ConversationLog()
        log.replace(window((1L..3L).map { entry(it, "ent_0000000000000000000000000$it") }))
        assertEquals(EntryFrameOutcome.Accept, log.applyEntryFrame(frame.seq, frame.prev, frame.entry))
        assertEquals(4L, log.cursor)
    }

    @Test
    fun unknownKeysSurvive() {
        val decoded = WireEntry.decode(load("wire/entries/unknown-keys.json"))
        val known = decoded as WireEntry.Known
        assertFalse(known.raw.keys.minus(KNOWN_KEYS).isEmpty())
    }

    @Test
    fun tailOffsetsAreUtf16() {
        val start = WireResume.applyTail(null, "m", 0, "Hi 👋🏽 there")
        assertEquals("Hi 👋🏽!", WireResume.applyTail(start, "m", 7, "!")?.text)
        // Past what's held: kept as is.
        assertEquals(start, WireResume.applyTail(start, "m", 99, "zzz"))
    }

    private val KNOWN_KEYS = setOf("seq", "id", "conversationId", "kind", "visibility", "author", "createdAt", "text", "blocks")

    @Suppress("unused")
    private fun JsonArray.longs() = map { it.long!! }
}
