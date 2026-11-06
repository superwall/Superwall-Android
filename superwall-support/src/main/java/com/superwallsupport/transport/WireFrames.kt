package com.superwallsupport.transport

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Socket frames on `GET /v1/conversations/:id/live`, mirroring
// `@superwall/inbox-contracts/wire/frames.ts`. A frame type this build doesn't know is ignored.

data class PresenceParticipant(
    val type: String,
    val name: String?,
    val avatarUrl: String?,
    val state: String,
)

sealed class ServerFrame {
    data class Ready(
        val conversationId: String,
        val after: Long,
        val head: Long,
    ) : ServerFrame()

    /** [entry] stays raw; the seq is outside it, so the cursor moves even when it doesn't decode. */
    data class Entry(
        val seq: Long,
        val prev: Long,
        val entry: JsonElement,
    ) : ServerFrame()

    data class Tail(
        val messageId: String,
        val offset: Int,
        val text: String,
    ) : ServerFrame()

    data class TailCancel(
        val messageId: String,
        val reason: String?,
    ) : ServerFrame()

    data class Status(
        val state: String,
        val etaSeconds: Int?,
    ) : ServerFrame()

    data class Presence(
        val participants: List<PresenceParticipant>,
    ) : ServerFrame()

    data class Resync(
        val reason: String,
        val head: Long,
    ) : ServerFrame()

    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : ServerFrame()

    data object Pong : ServerFrame()

    companion object {
        val KNOWN_TYPES = setOf("ready", "entry", "tail", "tail_cancel", "status", "presence", "resync", "error", "pong")
    }
}

sealed class DecodedFrame {
    data class Frame(
        val frame: ServerFrame,
    ) : DecodedFrame()

    /** Not JSON, not an object, an unknown `type`, or a known type that didn't decode. */
    data class Ignored(
        val reason: String,
    ) : DecodedFrame()
}

object WireFrameDecoder {
    /** Decodes one socket text frame. Never throws. */
    fun decode(text: String): DecodedFrame {
        val json = parseJson(text) ?: return DecodedFrame.Ignored("malformed_json")
        return decode(json)
    }

    fun decode(json: JsonElement): DecodedFrame {
        val type = (json as? JsonObject)?.get("type")?.stringOrNull ?: return DecodedFrame.Ignored("missing_type")
        if (type !in ServerFrame.KNOWN_TYPES) return DecodedFrame.Ignored("unknown_type:$type")
        return try {
            DecodedFrame.Frame(decodeKnown(type, WireFields(json)))
        } catch (issue: WireDecodeIssue) {
            DecodedFrame.Ignored(issue.message ?: "invalid")
        }
    }

    private fun decodeKnown(
        type: String,
        fields: WireFields,
    ): ServerFrame =
        when (type) {
            "ready" -> {
                fields.literal("protocol", setOf(SupportWire.PROTOCOL))
                ServerFrame.Ready(fields.id("conversationId", "conv"), fields.seq("after"), fields.seq("head"))
            }
            "entry" -> ServerFrame.Entry(fields.seq("seq"), fields.seq("prev"), fields.value("entry"))
            "tail" -> {
                val offset = fields.int("offset")
                if (offset < 0) throw WireDecodeIssue("offset", "Negative offset")
                ServerFrame.Tail(fields.id("messageId", "ent"), offset, fields.string("text"))
            }
            "tail_cancel" -> ServerFrame.TailCancel(fields.id("messageId", "ent"), fields.optionalString("reason"))
            "status" -> ServerFrame.Status(fields.string("state"), fields.optionalInt("etaSeconds"))
            "presence" ->
                ServerFrame.Presence(
                    fields.each("participants") { value, path ->
                        val participant = WireFields(value, path)
                        PresenceParticipant(
                            type = participant.literal("type", SupportWire.AUTHOR_TYPES),
                            name = participant.optionalString("name"),
                            avatarUrl = participant.optionalString("avatarUrl"),
                            state = participant.string("state"),
                        )
                    },
                )
            "resync" -> ServerFrame.Resync(fields.string("reason"), fields.seq("head"))
            "error" -> ServerFrame.Error(fields.string("code"), fields.string("message"), fields.bool("retryable"))
            else -> ServerFrame.Pong
        }
}

/** Client → server frames. */
sealed class ClientFrame {
    data class Hello(
        val sessionToken: String,
        val conversationId: String,
        val after: Long,
        val afterId: String?,
    ) : ClientFrame()

    data class Typing(
        val active: Boolean,
    ) : ClientFrame()

    data object Ping : ClientFrame()

    fun encoded(): String =
        when (this) {
            is Ping -> SupportWire.PING_FRAME
            is Typing ->
                buildJsonObject {
                    put("type", "typing")
                    put("active", active)
                }.toString()
            is Hello ->
                buildJsonObject {
                    put("type", "hello")
                    put("protocol", SupportWire.PROTOCOL)
                    put("sessionToken", sessionToken)
                    put("conversationId", conversationId)
                    put("after", JsonPrimitive(after))
                    // Omitted at 0: there is no entry to check.
                    if (after > 0 && afterId != null) put("afterId", afterId)
                }.toString()
        }

    companion object {
        /** The server's shape check, for the client-frame fixtures. */
        fun decode(text: String): ClientFrame? {
            val json = parseJson(text) ?: return null
            return try {
                val fields = WireFields(json)
                when (fields.string("type")) {
                    "ping" -> Ping
                    "typing" -> Typing(fields.bool("active"))
                    "hello" -> {
                        fields.string("protocol")
                        Hello(
                            fields.string("sessionToken"),
                            fields.id("conversationId", "conv"),
                            fields.seq("after"),
                            fields.optionalId("afterId", "ent"),
                        )
                    }
                    else -> null
                }
            } catch (_: WireDecodeIssue) {
                null
            }
        }
    }
}

/** WebSocket close codes and what each tells the client to do. */
object SupportCloseCode {
    const val NORMAL = 1000
    const val GOING_AWAY = 1001
    const val SESSION_INVALID = 4001
    const val PROTOCOL_UNSUPPORTED = 4002
    const val BAD_HELLO = 4003
    const val NOT_FOUND = 4004
    const val RESYNC = 4009
    const val TRY_LATER = 4029
    const val INTERNAL_ERROR = 1011
}

sealed class EntryFrameOutcome {
    /** New, and directly after the cursor: draw it and move the cursor. */
    data object Accept : EntryFrameOutcome()

    /** Already held: drop it. */
    data object Duplicate : EntryFrameOutcome()

    /** Something is missing between the cursor and this entry: reconnect with the cursor. */
    data class Gap(
        val expected: Long,
        val prev: Long,
    ) : EntryFrameOutcome()
}

/** A reply being streamed, keyed by the id its sealed entry will have. */
data class TailState(
    val messageId: String,
    val text: String,
)

/** Client half of the resume rules in `@superwall/inbox-contracts/wire/resume.ts`. */
object WireResume {
    fun applyEntryFrame(
        cursor: Long,
        seq: Long,
        prev: Long,
    ): EntryFrameOutcome =
        when {
            seq <= cursor -> EntryFrameOutcome.Duplicate
            prev != cursor -> EntryFrameOutcome.Gap(cursor, prev)
            else -> EntryFrameOutcome.Accept
        }

    /**
     * Truncate to [offset] (UTF-16 code units, as Kotlin strings are) and append, so a
     * replay is harmless. An offset past the held text follows a lost frame: keep what's held.
     */
    fun applyTail(
        current: TailState?,
        messageId: String,
        offset: Int,
        text: String,
    ): TailState? {
        val held = current?.takeIf { it.messageId == messageId }
        val base = held?.text ?: ""
        if (offset > base.length) return held
        return TailState(messageId, base.substring(0, offset) + text)
    }
}
