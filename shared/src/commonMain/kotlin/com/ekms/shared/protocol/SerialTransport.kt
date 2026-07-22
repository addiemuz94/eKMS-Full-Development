package com.ekms.shared.protocol

/**
 * Minimal byte-level transport [KeyCabinetLink] needs: open state, write,
 * clear-input-buffer, and a timed byte read. Opening/closing a physical
 * port is intentionally not part of this interface — that stays with the
 * concrete (Android-only) implementation and its caller, the same way
 * [KeyCabinetLink] only sends commands over an already-open transport.
 *
 * The real implementation (terminalApp, Android-only) wraps the vendor
 * serial AAR against `/dev/ttyS1`; tests substitute an in-memory fake
 * instead, so the command logic in [KeyCabinetLink] is verifiable with no
 * physical device attached.
 */
interface SerialTransport {
    val isOpen: Boolean

    fun write(bytes: ByteArray)

    /** Discards any bytes already buffered but not yet read. */
    fun clearInputBuffer()

    /** Returns the next byte (0-255), or null if none arrives within [timeoutMillis]. */
    fun readByteOrNull(timeoutMillis: Long): Int?
}
