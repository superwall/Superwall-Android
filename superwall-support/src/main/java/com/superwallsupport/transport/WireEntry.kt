package com.superwallsupport.transport

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Entries: the append-only conversation log as a person-side client sees it.
// Mirrors `@superwall/inbox-contracts/wire/entry.ts` and `lenient.ts`.

object SupportWire {
    /** The protocol this build speaks, sent at session start and in the socket's `hello`. */
    const val PROTOCOL = "superwall-support/1"

    /** Exactly this text, so a hibernating Durable Object answers without waking. */
    const val PING_FRAME = """{"type":"ping"}"""
    const val REDACTED_TEXT = "This message was removed."
    const val MAX_MESSAGE_LENGTH = 10_000
    const val DEFAULT_WINDOW = 60
    const val MAX_WINDOW = 200
    const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024

    /** Every entry kind this protocol version knows. Others decode as [WireEntry.Unknown]. */
    val KNOWN_ENTRY_KINDS =
        setOf(
            "person_message", "agent_message", "human_message", "note", "agent_feedback", "card",
            "card_response", "tool_call", "tool_result", "handoff", "status_change", "resolution",
            "system_notice",
        )

    internal val AUTHOR_TYPES = setOf("person", "agent", "human", "system", "external")
    internal val VISIBILITIES = setOf("public", "internal")
    internal val AUTOMATION_LEVELS = setOf("manual", "assisted", "autonomous", "external")
    private val MESSAGE_KINDS = setOf("person_message", "agent_message", "human_message", "note", "agent_feedback")

    internal fun isMessageKind(kind: String) = kind in MESSAGE_KINDS
}

data class EntryAuthor(
    val type: String,
    val name: String?,
    val avatarUrl: String?,
    val userId: Double?,
) {
    internal companion object {
        fun decode(
            value: JsonElement,
            path: String,
        ): EntryAuthor {
            val fields = WireFields(value, path)
            return EntryAuthor(
                type = fields.literal("type", SupportWire.AUTHOR_TYPES),
                name = fields.optionalString("name"),
                avatarUrl = fields.optionalString("avatarUrl"),
                userId = if (fields.has("userId")) fields.number("userId") else null,
            )
        }
    }
}

/** Server-parsed rich text. Clients never parse markdown. */
sealed class EntrySpan {
    data class Text(
        val text: String,
        val marks: List<String>,
    ) : EntrySpan()

    data class Link(
        val text: String,
        val href: String,
    ) : EntrySpan()

    internal companion object {
        private val MARKS = setOf("bold", "italic", "code", "strike")

        fun decode(
            value: JsonElement,
            path: String,
        ): EntrySpan {
            val fields = WireFields(value, path)
            return when (fields.literal("t", setOf("text", "link"))) {
                "text" -> {
                    val marks =
                        fields.optionalEach("marks") { mark, markPath ->
                            mark.stringOrNull?.takeIf { it in MARKS } ?: throw WireDecodeIssue(markPath, "Unknown mark")
                        }
                    Text(fields.string("text"), marks ?: emptyList())
                }
                else -> Link(fields.string("text"), fields.string("href"))
            }
        }
    }
}

sealed class EntryBlock {
    data class Paragraph(
        val spans: List<EntrySpan>,
    ) : EntryBlock()

    data class ListBlock(
        val ordered: Boolean,
        val items: List<List<EntrySpan>>,
    ) : EntryBlock()

    data class Quote(
        val spans: List<EntrySpan>,
    ) : EntryBlock()

    data class Code(
        val text: String,
    ) : EntryBlock()

    internal companion object {
        fun decode(
            value: JsonElement,
            path: String,
        ): EntryBlock {
            val fields = WireFields(value, path)
            return when (fields.literal("t", setOf("paragraph", "list", "quote", "code"))) {
                "paragraph" -> Paragraph(fields.each("spans", EntrySpan::decode))
                "list" ->
                    ListBlock(
                        ordered = fields.bool("ordered"),
                        items =
                            fields.each("items") { item, itemPath ->
                                val spans = item as? JsonArray ?: throw WireDecodeIssue(itemPath, "Expected an array")
                                spans.mapIndexed { index, span -> EntrySpan.decode(span, "$itemPath[$index]") }
                            },
                    )
                "quote" -> Quote(fields.each("spans", EntrySpan::decode))
                else -> Code(fields.string("text"))
            }
        }
    }
}

data class EntryAttachment(
    val id: String,
    val kind: String,
    val filename: String?,
    val mime: String,
    val bytes: Long,
    val status: String,
) {
    internal companion object {
        fun decode(
            value: JsonElement,
            path: String,
        ): EntryAttachment {
            val fields = WireFields(value, path)
            return EntryAttachment(
                id = fields.id("id", "att"),
                kind = fields.literal("kind", setOf("file", "diagnostics")),
                filename = fields.optionalString("filename"),
                mime = fields.string("mime"),
                bytes = fields.long("bytes"),
                status = fields.literal("status", setOf("quarantine", "clean", "rejected")),
            )
        }
    }
}

/** The card envelope. Nodes stay opaque JSON: the element set is the card-markup contract (M4). */
data class CardEnvelope(
    val id: String,
    val card: String,
    val tpl: String,
    val ui: Int,
    val text: String,
    val expiresAt: String?,
    val state: String?,
    val nodes: JsonArray,
    val bind: JsonObject?,
    /** Each action's JSON. `kind` is open; a client that doesn't know it sends `reply`. */
    val actions: Map<String, JsonObject>,
    val resolvedText: String?,
) {
    internal companion object {
        fun decode(
            value: JsonElement,
            path: String,
        ): CardEnvelope {
            val fields = WireFields(value, path)
            if (fields.int("v") != 1) throw WireDecodeIssue("$path.v", "Expected card envelope v1")
            val actions =
                fields.objectFields("actions").obj.mapValues { (key, action) ->
                    val actionFields = WireFields(action, "$path.actions.$key")
                    actionFields.string("kind")
                    actionFields.optionalString("reply")
                    actionFields.obj
                }
            return CardEnvelope(
                id = fields.id("id", "card"),
                card = fields.string("card"),
                tpl = fields.string("tpl"),
                ui = fields.int("ui"),
                text = fields.string("text"),
                expiresAt = fields.optionalTimestamp("expiresAt"),
                state = fields.optionalLiteral("state", setOf("live", "resolved", "expired")),
                nodes = fields.array("nodes"),
                bind = fields.optionalObject("bind"),
                actions = actions,
                resolvedText = if (fields.has("resolved")) fields.objectFields("resolved").string("text") else null,
            )
        }
    }
}

/** An entry of a kind this version knows, decoded strictly. */
data class KnownEntry(
    val seq: Long,
    val id: String,
    val conversationId: String,
    val kind: String,
    val visibility: String,
    val author: EntryAuthor,
    val createdAt: String,
    val automationLevel: String?,
    /** Erased: draw [text] ([SupportWire.REDACTED_TEXT]) and nothing else. */
    val redacted: Boolean,
    /** The plain-text fallback. Required on most kinds, optional on tool entries. */
    val text: String?,
    val body: Body,
) {
    sealed class Body {
        data class Message(
            val blocks: List<EntryBlock>?,
            val attachments: List<EntryAttachment>?,
            val clientMessageId: String?,
            val turnId: String?,
            val mentions: List<Double>?,
        ) : Body()

        data class Card(
            val card: CardEnvelope,
        ) : Body()

        data class CardResponse(
            val cardId: String,
            val actionId: String,
        ) : Body()

        data class ToolCall(
            val callId: String,
            val tool: String,
            val target: String,
            val args: JsonElement?,
            val state: String,
        ) : Body()

        data class ToolResult(
            val callId: String,
            val status: String,
        ) : Body()

        data class Handoff(
            val to: String,
            val reason: String?,
            val etaSeconds: Int?,
        ) : Body()

        data class StatusChange(
            val from: String?,
            val to: String,
        ) : Body()

        data class Resolution(
            val outcome: String,
            val actionId: String?,
        ) : Body()

        data class SystemNotice(
            val code: String,
        ) : Body()
    }

    val clientMessageId: String? get() = (body as? Body.Message)?.clientMessageId

    /** No message text: entries end up in logs. */
    override fun toString() = "KnownEntry(seq=$seq, id=$id, kind=$kind)"

    internal companion object {
        fun decode(value: JsonElement): KnownEntry {
            val fields = WireFields(value)
            val kind = fields.string("kind")
            val text =
                if (kind == "tool_call" || kind == "tool_result") fields.optionalString("text") else fields.string("text")
            val body =
                if (SupportWire.isMessageKind(kind)) {
                    Body.Message(
                        blocks = fields.optionalEach("blocks", EntryBlock::decode),
                        attachments =
                            if (kind == "person_message" || kind == "human_message") {
                                fields.optionalEach("attachments", EntryAttachment::decode)
                            } else {
                                null
                            },
                        clientMessageId =
                            if (kind == "person_message") {
                                fields.optionalString("clientMessageId")?.also {
                                    if (!WireIds.isClientMessageId(it)) {
                                        throw WireDecodeIssue("clientMessageId", "Expected a client message id")
                                    }
                                }
                            } else {
                                null
                            },
                        turnId =
                            if (kind == "agent_message" || kind == "agent_feedback") fields.optionalId("turnId", "turn") else null,
                        mentions =
                            if (kind == "note") {
                                fields.optionalEach("mentions") { mention, path ->
                                    mention.numberOrNull ?: throw WireDecodeIssue(path, "Expected a number")
                                }
                            } else {
                                null
                            },
                    )
                } else {
                    when (kind) {
                        "card" -> Body.Card(CardEnvelope.decode(fields.value("card"), "card"))
                        "card_response" -> Body.CardResponse(fields.id("cardId", "card"), fields.string("actionId"))
                        "tool_call" ->
                            Body.ToolCall(
                                callId = fields.id("callId", "call"),
                                tool = fields.string("tool"),
                                target = fields.literal("target", setOf("app", "server")),
                                args = fields.obj["args"],
                                state = fields.string("state"),
                            )
                        "tool_result" ->
                            Body.ToolResult(fields.id("callId", "call"), fields.literal("status", setOf("ok", "error", "declined")))
                        "handoff" ->
                            Body.Handoff(
                                to = fields.literal("to", setOf("human", "agent")),
                                reason = fields.optionalString("reason"),
                                etaSeconds = fields.optionalInt("etaSeconds"),
                            )
                        "status_change" -> Body.StatusChange(fields.optionalString("from"), fields.string("to"))
                        "resolution" -> Body.Resolution(fields.string("outcome"), fields.optionalId("actionId", "act"))
                        "system_notice" -> Body.SystemNotice(fields.string("code"))
                        else -> throw WireDecodeIssue("kind", "Not a known kind")
                    }
                }
            return KnownEntry(
                seq = fields.seq("seq"),
                id = fields.id("id", "ent"),
                conversationId = fields.id("conversationId", "conv"),
                kind = kind,
                visibility = fields.literal("visibility", SupportWire.VISIBILITIES),
                author = EntryAuthor.decode(fields.value("author"), "author"),
                createdAt = fields.timestamp("createdAt"),
                automationLevel = fields.optionalLiteral("automationLevel", SupportWire.AUTOMATION_LEVELS),
                redacted = fields.optionalBool("redacted") ?: false,
                text = text,
                body = body,
            )
        }
    }
}

/** One entry, decoded on its own so a bad one never blanks a conversation. */
sealed class WireEntry {
    abstract val seq: Long?
    abstract val id: String?
    abstract val kind: String?

    /** What a client draws when it can't fully render the entry. */
    abstract val fallbackText: String?

    /** A kind this version knows, and it decoded. [raw] keeps unknown keys. */
    data class Known(
        val entry: KnownEntry,
        val raw: JsonObject,
    ) : WireEntry() {
        override val seq get() = entry.seq
        override val id get() = entry.id
        override val kind get() = entry.kind
        override val fallbackText get() = entry.text
    }

    /** A kind this version doesn't know. Drawn from `text` if present, else skipped. */
    data class Unknown(
        override val seq: Long,
        override val id: String,
        override val kind: String,
        val text: String?,
        val raw: JsonObject,
    ) : WireEntry() {
        override val fallbackText get() = text
    }

    /** A known kind that didn't decode, or JSON with no usable envelope. */
    data class Failed(
        override val seq: Long?,
        override val id: String?,
        override val kind: String?,
        val text: String?,
        val redacted: Boolean,
        val issue: String,
        val raw: JsonElement,
    ) : WireEntry() {
        override val fallbackText get() = text
    }

    val clientMessageId: String? get() = (this as? Known)?.entry?.clientMessageId

    companion object {
        /** Decodes one entry from JSON. Never throws. */
        fun decode(raw: JsonElement): WireEntry {
            val obj = raw as? JsonObject
            val seq = obj?.get("seq")?.intOrNull?.takeIf { it >= 0 }
            val id = obj?.get("id")?.stringOrNull?.takeIf { WireIds.matches(it, "ent") }
            val kind = obj?.get("kind")?.stringOrNull
            val textElement = obj?.get("text")
            val text = textElement?.stringOrNull
            val redacted = obj?.get("redacted")?.boolOrNull == true
            if (obj == null || seq == null || id == null || kind == null || (textElement != null && text == null)) {
                // Keep every identity field that validates on its own: with the id, a client
                // can still match this entry to the tail it seals.
                return Failed(seq, id, kind, text, redacted, "No usable envelope", raw)
            }
            if (kind !in SupportWire.KNOWN_ENTRY_KINDS) return Unknown(seq, id, kind, text, obj)
            return try {
                Known(KnownEntry.decode(raw), obj)
            } catch (issue: WireDecodeIssue) {
                Failed(seq, id, kind, text, redacted, issue.message ?: "Invalid", raw)
            }
        }

        /** A window's entries, one by one. A non-array decodes to none. */
        fun decodeAll(raw: JsonElement): List<WireEntry> = (raw as? JsonArray)?.map(::decode) ?: emptyList()
    }
}
