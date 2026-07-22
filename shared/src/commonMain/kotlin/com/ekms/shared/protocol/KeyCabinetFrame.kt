package com.ekms.shared.protocol

/**
 * Key Cabinet Communication Protocol, section 4 — one decoded frame's
 * logical fields: Box Address + Node Address + Command + optional Data Area.
 *
 * CRC8 is never stored here; it is always derived fresh from these fields
 * via [crc8], matching section 6.4 ("CRC8 is calculated over the raw data").
 */
class KeyCabinetFrame(
    val boxAddress: Int,
    val nodeAddress: Int,
    val command: Int,
    val data: ByteArray = ByteArray(0),
) {
    init {
        require(boxAddress in 0..0xFF) { "Box Address must be 0-255." }
        require(nodeAddress in 0..0xFF) { "Node Address must be 0-255." }
        require(command in 0..0xFF) { "Command must be 0-255." }
    }

    /** Raw (unsplit) Box Address + Node Address + Command + Data Area, the CRC8 input. */
    fun rawPayload(): ByteArray =
        byteArrayOf(boxAddress.toByte(), nodeAddress.toByte(), command.toByte()) + data

    fun crc8(): Int = KeyCabinetCrc8.calculate(rawPayload())

    override fun equals(other: Any?): Boolean =
        other is KeyCabinetFrame &&
                boxAddress == other.boxAddress &&
                nodeAddress == other.nodeAddress &&
                command == other.command &&
                data.contentEquals(other.data)

    override fun hashCode(): Int {
        var result = boxAddress
        result = 31 * result + nodeAddress
        result = 31 * result + command
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String =
        "KeyCabinetFrame(boxAddress=$boxAddress, nodeAddress=$nodeAddress, " +
                "command=0x${command.toString(16)}, data=${data.joinToString(prefix = "[", postfix = "]") { "0x" + (it.toInt() and 0xFF).toString(16) }})"
}

/**
 * Key Cabinet Communication Protocol, section 4 (assembly) and section
 * 7.5-7.6 (parsing) — builds and parses the physical transmission frame
 * around a [KeyCabinetFrame]'s raw fields.
 *
 * Host -> node frames use start/end bytes `0x02`/`0x04`; node -> host reply
 * frames use `0x01`/`0x03`. This object only assembles/parses bytes — it
 * never opens a serial port (see terminalApp's hardware layer for that).
 */
object KeyCabinetFrameCodec {
    const val HOST_START_BYTE = 0x02
    const val HOST_END_BYTE = 0x04
    const val NODE_START_BYTE = 0x01
    const val NODE_END_BYTE = 0x03

    /** Builds a host -> node command frame: `0x02` + split-encoded payload+CRC8 + `0x04`. */
    fun buildHostFrame(frame: KeyCabinetFrame): ByteArray =
        buildTransmissionFrame(frame, HOST_START_BYTE, HOST_END_BYTE)

    /** Builds a node -> host reply frame: `0x01` + split-encoded payload+CRC8 + `0x03`. */
    fun buildNodeFrame(frame: KeyCabinetFrame): ByteArray =
        buildTransmissionFrame(frame, NODE_START_BYTE, NODE_END_BYTE)

    /**
     * Parses a node -> host reply frame. Returns null — discard, per section
     * 7.5 — if the start/end bytes don't match, the encoded body has an odd
     * length or an out-of-range byte, or the CRC8 does not verify.
     */
    fun parseNodeFrame(transmissionFrame: ByteArray): KeyCabinetFrame? =
        parseTransmissionFrame(transmissionFrame, NODE_START_BYTE, NODE_END_BYTE)

    /** Parses a host -> node command frame (mirrors [parseNodeFrame] for the other direction). */
    fun parseHostFrame(transmissionFrame: ByteArray): KeyCabinetFrame? =
        parseTransmissionFrame(transmissionFrame, HOST_START_BYTE, HOST_END_BYTE)

    private fun buildTransmissionFrame(frame: KeyCabinetFrame, startByte: Int, endByte: Int): ByteArray {
        val rawWithCrc = frame.rawPayload() + frame.crc8().toByte()
        val encodedBody = SplitNibbleCodec.encode(rawWithCrc)
        return byteArrayOf(startByte.toByte()) + encodedBody + byteArrayOf(endByte.toByte())
    }

    private const val MIN_RAW_PAYLOAD_SIZE = 4 // boxAddress + nodeAddress + command + crc8

    private fun parseTransmissionFrame(
        transmissionFrame: ByteArray,
        expectedStartByte: Int,
        expectedEndByte: Int,
    ): KeyCabinetFrame? {
        if (transmissionFrame.size < 2) return null

        val start = transmissionFrame.first().toInt() and 0xFF
        val end = transmissionFrame.last().toInt() and 0xFF
        if (start != expectedStartByte || end != expectedEndByte) return null

        val encodedBody = transmissionFrame.copyOfRange(1, transmissionFrame.size - 1)
        val rawWithCrc = SplitNibbleCodec.decode(encodedBody) ?: return null
        if (rawWithCrc.size < MIN_RAW_PAYLOAD_SIZE) return null

        val payload = rawWithCrc.copyOfRange(0, rawWithCrc.size - 1)
        val receivedCrc = rawWithCrc.last().toInt() and 0xFF
        val expectedCrc = KeyCabinetCrc8.calculate(payload)
        if (receivedCrc != expectedCrc) return null

        return KeyCabinetFrame(
            boxAddress = payload[0].toInt() and 0xFF,
            nodeAddress = payload[1].toInt() and 0xFF,
            command = payload[2].toInt() and 0xFF,
            data = payload.copyOfRange(3, payload.size),
        )
    }
}
