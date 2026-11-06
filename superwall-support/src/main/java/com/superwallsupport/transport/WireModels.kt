package com.superwallsupport.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Session start and HTTP command payloads, mirroring
// `@superwall/inbox-contracts/wire/session.ts` and `commands.ts`.

@Serializable
data class SupportSdk(
    val platform: String,
    val version: String,
    val addonVersion: String? = null,
)

@Serializable
data class SupportCapabilities(
    val ui: Ui,
    val cards: List<String>,
    /** Open bridge verbs: `purchase`, `restore`, `manage`, `refund`, `open_url`, `tool` … */
    val actions: List<String>,
    val entryKinds: List<String>? = null,
    val attachments: Boolean? = null,
) {
    @Serializable
    data class Ui(
        val v: Int,
        val elements: Map<String, Int>,
    )

    companion object {
        /** What the transport-only build can draw: text for everything (M2 adds renderers). */
        val TEXT_ONLY = SupportCapabilities(Ui(1, emptyMap()), emptyList(), emptyList(), attachments = true)
    }
}

@Serializable
data class SupportAppTool(
    val name: String,
    val version: String,
    val schemaHash: String,
)

/** One proof the session presents. [value] is a secret: never logged. */
@Serializable
data class SupportProof(
    val kind: String,
    val value: String,
) {
    override fun toString() = "SupportProof(kind=$kind)"
}

@Serializable
data class SessionStart(
    val protocol: String = SupportWire.PROTOCOL,
    val apiKey: String,
    val sdk: SupportSdk,
    val locale: String,
    val storefront: String? = null,
    val capabilities: SupportCapabilities,
    val appTools: List<SupportAppTool>? = null,
    val proofs: List<SupportProof>,
    val conversationId: String? = null,
)

@Serializable
data class SessionStarted(
    val protocol: String,
    val sessionId: String,
    val sessionToken: String,
    val expiresAt: String,
    val applicationId: Long,
    val conversationId: String,
    val proofs: List<AcceptedProof>,
    val caps: SupportCapabilities,
    val disclosure: String? = null,
    val consentRequired: Boolean,
    /** The computed palette (theme contract, M2). Opaque here. */
    val theme: JsonObject,
    val human: Human,
    /** Present when this build must not open chat. */
    val redirect: Redirect? = null,
    val heartbeatSeconds: Int,
) {
    @Serializable
    data class AcceptedProof(
        val kind: String,
        val level: String,
        val expiresAt: String? = null,
    )

    @Serializable
    data class Human(
        val available: Boolean,
        val etaSeconds: Int? = null,
    )

    @Serializable
    data class Redirect(
        val reason: String,
        val url: String,
    )
}

@Serializable
data class EntryWindow(
    val conversationId: String,
    val entries: List<JsonElement>,
    val head: Long,
    val hasMore: Boolean,
) {
    val decodedEntries: List<WireEntry> get() = entries.map(WireEntry::decode)
}

@Serializable
data class AttachmentRef(
    val attachmentId: String,
)

@Serializable
data class SendMessage(
    val clientMessageId: String,
    val text: String,
    val attachments: List<AttachmentRef>? = null,
    val seenSeq: Long? = null,
) {
    /** The rules the server enforces, checked before a send is queued. */
    fun validate() {
        if (!WireIds.isClientMessageId(clientMessageId)) throw SupportTransportException.InvalidMessage("clientMessageId")
        // Kotlin's length counts UTF-16 code units, like the schema's JavaScript.
        if (text.length > SupportWire.MAX_MESSAGE_LENGTH) {
            throw SupportTransportException.MessageTooLong(SupportWire.MAX_MESSAGE_LENGTH)
        }
    }
}

@Serializable
data class SendAccepted(
    val entry: JsonElement,
    val duplicate: Boolean,
)

@Serializable
data class MarkRead(
    val seq: Long,
)

@Serializable
data class UploadRequest(
    val kind: String,
    val filename: String,
    val mime: String,
    val bytes: Long,
    val sha256: String,
)

@Serializable
data class UploadIntent(
    val attachmentId: String,
    val upload: Upload,
) {
    @Serializable
    data class Upload(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val expiresAt: String,
    )
}

@Serializable
data class UploadCompleted(
    val attachmentId: String,
    val status: String,
)

@Serializable
data class AttachmentUrl(
    val url: String,
    val expiresAt: String,
)

@Serializable
data class CardActionRequest(
    val actionId: String,
    val token: String,
    val values: JsonObject? = null,
)

@Serializable
data class CardActionAccepted(
    val entries: List<JsonElement>,
)

@Serializable
data class ToolCallResultRequest(
    val status: String,
    val data: JsonElement? = null,
)

/** An HTTP error body: `_tag` is the stable code; `retryable` covers tags this build doesn't know. */
@Serializable
data class WireErrorBody(
    @SerialName("_tag") val tag: String,
    val message: String,
    val retryable: Boolean? = null,
    val redirect: String? = null,
    val reason: String? = null,
    val retryAfterMs: Long? = null,
) {
    /** The schema defaults `retryable` to true for these tags when a body omits it. */
    val isRetryable: Boolean get() = retryable ?: (tag == "RateLimited" || tag == "WireUnavailable")
}

sealed class SupportTransportException(
    message: String,
) : Exception(message) {
    class MessageTooLong(
        val max: Int,
    ) : SupportTransportException("Message is longer than $max")

    class InvalidMessage(
        field: String,
    ) : SupportTransportException("Invalid $field")

    class AttachmentTooLarge(
        val max: Int,
    ) : SupportTransportException("Attachment is empty or larger than $max bytes")

    /** The server answered with a wire error body. */
    class Wire(
        val body: WireErrorBody,
    ) : SupportTransportException("${body.tag}: ${body.message}")

    /** No response, or one we couldn't read. Safe to retry: commands are idempotent. */
    class Network(
        detail: String,
    ) : SupportTransportException(detail)

    class NotStarted : SupportTransportException("Not started")

    class Redirected(
        val url: String,
    ) : SupportTransportException("Redirected")

    val isRetryable: Boolean
        get() =
            when (this) {
                is Wire -> body.isRetryable
                is Network -> true
                else -> false
            }
}
