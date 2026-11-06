package com.superwallsupport.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The HTTP half of `superwall-support/1`. Every command is idempotent, so the
 * caller may retry any retryable failure as-is.
 */
internal class SupportHttpClient(
    baseUrl: String,
    private val client: OkHttpClient,
    /** For presigned uploads: no overall call timeout. */
    private val uploadClient: OkHttpClient = client,
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()
    private val jsonType = "application/json".toMediaType()

    suspend fun startSession(request: SessionStart): SessionStarted =
        send("POST", "/v1/sessions", null, encode(SessionStart.serializer(), request), SessionStarted.serializer())

    suspend fun entries(
        token: String,
        conversationId: String,
        after: Long? = null,
        before: Long? = null,
        limit: Int = SupportWire.DEFAULT_WINDOW,
    ): EntryWindow {
        val query =
            buildList {
                add("limit" to limit.coerceIn(1, SupportWire.MAX_WINDOW).toString())
                after?.let { add("after" to it.toString()) }
                before?.let { add("before" to it.toString()) }
            }
        return send("GET", "/v1/conversations/$conversationId/entries", token, null, EntryWindow.serializer(), query)
    }

    suspend fun sendMessage(
        token: String,
        conversationId: String,
        message: SendMessage,
    ): SendAccepted =
        send(
            "POST",
            "/v1/conversations/$conversationId/messages",
            token,
            encode(SendMessage.serializer(), message),
            SendAccepted.serializer(),
        )

    suspend fun markRead(
        token: String,
        conversationId: String,
        seq: Long,
    ) {
        send<Unit>("POST", "/v1/conversations/$conversationId/read", token, encode(MarkRead.serializer(), MarkRead(seq)), null)
    }

    suspend fun createUpload(
        token: String,
        conversationId: String,
        request: UploadRequest,
    ): UploadIntent =
        send(
            "POST",
            "/v1/conversations/$conversationId/attachments",
            token,
            encode(UploadRequest.serializer(), request),
            UploadIntent.serializer(),
        )

    suspend fun completeUpload(
        token: String,
        attachmentId: String,
    ): UploadCompleted = send("POST", "/v1/attachments/$attachmentId/complete", token, "", UploadCompleted.serializer())

    suspend fun attachmentUrl(
        token: String,
        attachmentId: String,
    ): AttachmentUrl = send("GET", "/v1/attachments/$attachmentId/url", token, null, AttachmentUrl.serializer())

    suspend fun cardAction(
        token: String,
        cardId: String,
        request: CardActionRequest,
    ): CardActionAccepted =
        send(
            "POST",
            "/v1/cards/$cardId/actions",
            token,
            encode(CardActionRequest.serializer(), request),
            CardActionAccepted.serializer(),
        )

    suspend fun toolCallResult(
        token: String,
        callId: String,
        request: ToolCallResultRequest,
    ) {
        send<Unit>("POST", "/v1/tool-calls/$callId/result", token, encode(ToolCallResultRequest.serializer(), request), null)
    }

    /** The presigned PUT of an upload intent: straight to storage, no bearer token. */
    suspend fun put(
        upload: UploadIntent.Upload,
        bytes: ByteArray,
    ) {
        val contentType = upload.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
        val builder =
            Request
                .Builder()
                .url(upload.url)
                .method(upload.method, bytes.toRequestBody(contentType?.toMediaType()))
        upload.headers.forEach { (name, value) -> builder.header(name, value) }
        val response = execute(builder.build(), uploadClient)
        response.use {
            if (!it.isSuccessful) {
                // Storage errors carry no wire body; a 4xx (an expired link) needs a new intent.
                throw SupportTransportException.Wire(
                    WireErrorBody("UploadFailed", "Upload answered ${it.code}", retryable = it.code >= 500),
                )
            }
        }
    }

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ) = wireJson.encodeToString(serializer, value)

    private suspend fun <T> send(
        method: String,
        path: String,
        token: String?,
        body: String?,
        responseSerializer: KSerializer<T>?,
        query: List<Pair<String, String>> = emptyList(),
    ): T {
        val url =
            base
                .newBuilder()
                .addPathSegments(path.trimStart('/'))
                .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
        val builder =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .method(method, body?.toRequestBody(jsonType))
        token?.let { builder.header("Authorization", "Bearer $it") }
        val response = execute(builder.build())
        // The body is read off the transport's thread, and a reset or timeout while reading
        // it is a retryable network error like any other.
        val text =
            try {
                withContext(Dispatchers.IO) { response.use { it.body?.string() ?: "" } }
            } catch (error: IOException) {
                throw SupportTransportException.Network(error.javaClass.simpleName)
            }
        if (!response.isSuccessful) {
            val error =
                try {
                    wireJson.decodeFromString(WireErrorBody.serializer(), text)
                } catch (_: Exception) {
                    // No wire body (a proxy, a crash): 5xx, 408, 425 and 429 are worth retrying.
                    WireErrorBody(
                        "HTTP${response.code}",
                        "HTTP ${response.code}",
                        retryable = response.code >= 500 || response.code in setOf(408, 425, 429),
                    )
                }
            throw SupportTransportException.Wire(error)
        }
        @Suppress("UNCHECKED_CAST")
        if (responseSerializer == null) return Unit as T
        return try {
            wireJson.decodeFromString(responseSerializer, text)
        } catch (error: Exception) {
            // A 2xx we can't read: retrying the same idempotent command is the safe answer.
            // Only the class name: the decoder's message quotes the body (tokens, message text).
            throw SupportTransportException.Network("Undecodable response: ${error.javaClass.simpleName}")
        }
    }

    private suspend fun execute(
        request: Request,
        using: OkHttpClient = client,
    ): Response =
        suspendCancellableCoroutine { continuation ->
            val call = using.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(SupportTransportException.Network(e.javaClass.simpleName))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        // Cancelled meanwhile: close it rather than leak the connection.
                        continuation.resume(response) { response.close() }
                    }
                },
            )
        }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
