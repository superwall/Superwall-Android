package com.superwallsupport.transport

/**
 * What a client holds of one conversation: entries by seq, the cursor it resumes
 * from, the streaming tail and the status outside the log.
 *
 * The cursor only moves through contiguous delivery (socket frames checked with
 * `prev`, or HTTP windows read in order). Entries that arrive any other way, like
 * the entry a send returns, are held without moving it, so the socket still
 * delivers them in order and they are de-duplicated by seq.
 */
class ConversationLog {
    var conversationId: String? = null
        private set

    /** The last seq held through contiguous delivery; 0 for none. */
    var cursor: Long = 0
        private set

    /** The id of the entry at [cursor], when it decoded far enough to have one. */
    var cursorId: String? = null
        private set
    private val bySeq = sortedMapOf<Long, WireEntry>()
    private val heldIds = HashSet<String>()
    var tail: TailState? = null
        private set
    var status: String? = null
        private set
    var statusEtaSeconds: Int? = null
        private set
    var presence: List<PresenceParticipant> = emptyList()
        private set

    /** More entries exist before the oldest one held. */
    var hasOlder = false
        private set

    /** Entries in seq order. */
    val entries: List<WireEntry> get() = bySeq.values.toList()

    val oldestSeq: Long? get() = if (bySeq.isEmpty()) null else bySeq.firstKey()

    fun entry(seq: Long): WireEntry? = bySeq[seq]

    internal fun reset(conversationId: String) {
        clear()
        this.conversationId = conversationId
    }

    private fun clear() {
        cursor = 0
        cursorId = null
        bySeq.clear()
        heldIds.clear()
        tail = null
        status = null
        statusEtaSeconds = null
        presence = emptyList()
        hasOlder = false
    }

    /** Replaces everything with the newest window (open, or after `resync`). */
    internal fun replace(window: EntryWindow) {
        val id = conversationId ?: window.conversationId
        clear()
        conversationId = id
        window.decodedEntries.forEach(::insertReadInOrder)
        hasOlder = window.hasMore
    }

    /** A window read with `after=cursor`: contiguous, so it moves the cursor. */
    internal fun applyCatchUp(window: EntryWindow) {
        window.decodedEntries.forEach(::insertReadInOrder)
    }

    /** A window read with `before=oldest`: older entries; the cursor stays. */
    internal fun applyOlder(window: EntryWindow) {
        window.decodedEntries.forEach { entry -> entry.seq?.let { hold(it, entry) } }
        hasOlder = window.hasMore
    }

    private fun insertReadInOrder(entry: WireEntry) {
        // No seq: can't be placed. The cursor stays, so a later socket replays it with its seq.
        val seq = entry.seq ?: return
        if (seq <= cursor) return
        hold(seq, entry)
        cursor = seq
        cursorId = entry.id
    }

    private fun hold(
        seq: Long,
        entry: WireEntry,
    ) {
        bySeq[seq] = entry
        entry.id?.let { id ->
            heldIds.add(id)
            if (tail?.messageId == id) tail = null
        }
    }

    /** An `entry` frame, under the resume rules. */
    internal fun applyEntryFrame(
        seq: Long,
        prev: Long,
        raw: kotlinx.serialization.json.JsonElement,
    ): EntryFrameOutcome {
        val outcome = WireResume.applyEntryFrame(cursor, seq, prev)
        if (outcome != EntryFrameOutcome.Accept) return outcome
        val entry = WireEntry.decode(raw)
        // The frame's seq is authoritative: the cursor moves even when the entry doesn't decode.
        hold(seq, entry)
        cursor = seq
        cursorId = entry.id
        return outcome
    }

    /** An entry that arrived outside the stream (a send's response). Held; the cursor stays. */
    internal fun holdOutOfBand(entry: WireEntry) {
        val seq = entry.seq ?: return
        if (bySeq.containsKey(seq)) return
        hold(seq, entry)
    }

    /** `ready`: tails are never persisted, so drop any held one; a live tail is re-sent. */
    internal fun ready() {
        tail = null
    }

    internal fun applyTail(
        messageId: String,
        offset: Int,
        text: String,
    ) {
        // A tail for an entry already held is stale: its seal came first.
        if (messageId in heldIds) return
        tail = WireResume.applyTail(tail, messageId, offset, text)
    }

    internal fun cancelTail(messageId: String) {
        if (tail?.messageId == messageId) tail = null
    }

    internal fun setStatus(
        state: String,
        etaSeconds: Int?,
    ) {
        status = state
        statusEtaSeconds = etaSeconds
    }

    internal fun setPresence(participants: List<PresenceParticipant>) {
        presence = participants
    }
}
