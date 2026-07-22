package com.ekms.shared.protocol

/**
 * In-memory [SerialTransport] test double — lets [KeyCabinetLink] be
 * verified with no physical device attached.
 *
 * Each call to [write] consumes one queued response registered via
 * [enqueueResponse]: a `ByteArray` is fed back byte-by-byte on subsequent
 * [readByteOrNull] calls (simulating a node reply), and `null` simulates a
 * full timeout (no response at all) for that attempt. [writtenFrames]
 * records every frame sent, so a test can assert exactly what — and how
 * many times — something was transmitted (e.g. to verify retry behavior,
 * or that a blocked electromagnet command was never sent).
 */
class FakeSerialTransport : SerialTransport {
    override var isOpen: Boolean = true

    val writtenFrames = mutableListOf<ByteArray>()

    private val queuedResponses = ArrayDeque<ByteArray?>()
    private var currentResponseBytes: ArrayDeque<Int>? = null

    /** Queues the reply bytes the next [write] should receive; null simulates a full timeout. */
    fun enqueueResponse(replyBytes: ByteArray?) {
        queuedResponses.addLast(replyBytes)
    }

    override fun write(bytes: ByteArray) {
        writtenFrames.add(bytes)
        val next = if (queuedResponses.isNotEmpty()) queuedResponses.removeFirst() else null
        currentResponseBytes = next?.let { reply -> ArrayDeque(reply.map { it.toInt() and 0xFF }) }
    }

    override fun clearInputBuffer() {
        currentResponseBytes?.clear()
    }

    override fun readByteOrNull(timeoutMillis: Long): Int? {
        val queue = currentResponseBytes ?: return null
        return if (queue.isEmpty()) null else queue.removeFirst()
    }
}
