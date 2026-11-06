package com.superwallsupport.transport

import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** One WebSocket, as the transport needs it. The real one is OkHttp's; tests use a fake. */
interface SupportSocket {
    /** Queues a text frame. False when the socket is already closed. */
    fun send(text: String): Boolean

    /** The next text frame; throws [Closed] once the socket closes or fails. */
    suspend fun receive(): String

    fun close(
        code: Int,
        reason: String,
    )

    /** The close code the server sent, once closed. */
    val closeCode: Int?

    class Closed(
        val code: Int?,
        detail: String,
    ) : Exception(detail)
}

fun interface SupportSocketFactory {
    fun make(url: String): SupportSocket
}

internal class OkHttpSupportSocket(
    client: OkHttpClient,
    url: String,
) : WebSocketListener(),
    SupportSocket {
    private val inbox = Channel<String>(Channel.UNLIMITED)

    @Volatile
    override var closeCode: Int? = null
        private set

    // OkHttp offers permessage-deflate on its own (M0 spike, OkHttp 4.12).
    private val socket: WebSocket = client.newWebSocket(Request.Builder().url(url).build(), this)

    override fun send(text: String) = socket.send(text)

    override suspend fun receive(): String =
        inbox.receiveCatching().getOrNull() ?: throw SupportSocket.Closed(closeCode, "closed")

    override fun close(
        code: Int,
        reason: String,
    ) {
        if (!socket.close(code, reason)) socket.cancel()
        inbox.close()
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        inbox.trySend(text)
    }

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) {
        inbox.trySend(bytes.utf8())
    }

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        closeCode = code
        webSocket.close(code, null)
        inbox.close()
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        closeCode = closeCode ?: code
        inbox.close()
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        inbox.close()
    }
}
