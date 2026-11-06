package com.superwallsupport.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * A message the person sent that the server hasn't confirmed. Persisted, so a send
 * survives the process dying: the same [clientMessageId] is retried until the server
 * confirms it, and the server lands it once.
 */
@Serializable
data class PendingMessage(
    val clientMessageId: String,
    val conversationId: String,
    val text: String,
    val attachmentIds: List<String>,
    val createdAtMillis: Long,
    val attempts: Int = 0,
    val state: State = State.PENDING,
    /** The wire error tag of a failed send. Never message text. */
    val failure: String? = null,
) {
    @Serializable
    enum class State { PENDING, FAILED }

    override fun toString() = "PendingMessage($clientMessageId, $state, attempts=$attempts)"
}

/** Pending sends in the order they were made, in one JSON file, written atomically. */
internal class SendQueueStore(
    private val file: File?,
    private val logger: (String) -> Unit = {},
) {
    private val serializer = ListSerializer(PendingMessage.serializer())
    private var items: MutableList<PendingMessage> = load()

    private fun load(): MutableList<PendingMessage> {
        if (file == null || !file.exists()) return mutableListOf()
        return try {
            wireJson.decodeFromString(serializer, file.readText()).toMutableList()
        } catch (error: Exception) {
            // Unreadable (corrupt, or from another version): set it aside rather than overwrite it.
            logger("send queue unreadable (${error.javaClass.simpleName}); kept as .unreadable")
            file.renameTo(File(file.parentFile, file.name + ".unreadable"))
            mutableListOf()
        }
    }

    @get:Synchronized
    val all: List<PendingMessage> get() = items.toList()

    @Synchronized
    fun append(item: PendingMessage) = mutate { it.add(item) }

    @Synchronized
    fun remove(clientMessageId: String): Boolean {
        var removed = false
        mutate { list -> removed = list.removeAll { it.clientMessageId == clientMessageId } }
        return removed
    }

    @Synchronized
    fun update(
        clientMessageId: String,
        change: (PendingMessage) -> PendingMessage,
    ) = mutate { list ->
        val index = list.indexOfFirst { it.clientMessageId == clientMessageId }
        if (index >= 0) list[index] = change(list[index])
    }

    @Synchronized
    fun next(conversationId: String): PendingMessage? =
        items.firstOrNull { it.conversationId == conversationId && it.state == PendingMessage.State.PENDING }

    private fun mutate(change: (MutableList<PendingMessage>) -> Unit) {
        change(items)
        val file = file ?: return
        // Write then rename, so a crash mid-write leaves the previous file. A failed write
        // (a full disk) keeps the queue in memory rather than crashing the app.
        try {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(wireJson.encodeToString(serializer, items))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (error: java.io.IOException) {
            logger("send queue not saved (${error.javaClass.simpleName})")
        }
    }
}
