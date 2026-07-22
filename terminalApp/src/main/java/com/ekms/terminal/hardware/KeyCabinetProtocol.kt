package com.ekms.terminal.hardware

import java.util.Locale

/**
 * Supplier key-cabinet serial protocol for /dev/ttyS1 at 19,200 baud.
 *
 * Host frames are delimited by 0x02/0x04. Node replies are delimited by
 * 0x01/0x03. Every byte in the payload uses the supplier split-nibble
 * encoding and the documented CRC8 algorithm.
 */
class KeyCabinetProtocol(
    private val serialPort: CabinetSerialPort,
    private val boxAddress: Int,
) {
    companion object {
        const val DOOR_NODE_ADDRESS = 0
        /** Per docs/Key Cabinet Communication Protocol.md §7.1: key nodes are 1-127. */
        const val MAX_KEY_NODE_ADDRESS = 127

        private const val COMMAND_BLUE_LIGHT_ON = 0x11
        private const val COMMAND_BLUE_LIGHT_OFF = 0x12
        private const val COMMAND_ELECTROMAGNET_ENGAGE = 0x13
        private const val COMMAND_ELECTROMAGNET_RELEASE = 0x14
        private const val COMMAND_READ_KEY_CARD = 0x15
        private const val COMMAND_CHECK_KEY_BOLT = 0x16
        private const val COMMAND_CHECK_NODE_STATUS = 0x17
        private const val COMMAND_RED_LIGHT_ON = 0x19
        private const val COMMAND_RED_LIGHT_OFF = 0x1A
        private const val COMMAND_CHECK_DOOR_STATUS = 0x22
        private const val COMMAND_EJECT_DOOR = 0x23

        /** Per docs/Key Cabinet Communication Protocol.md §7.4. */
        private const val RESPONSE_TIMEOUT_MILLIS = 500L
        private const val MAX_SEND_ATTEMPTS = 3
    }

    init {
        require(boxAddress in 1..255) { "Box Address must be from 1 to 255." }
    }

    fun checkDoorStatus(): CabinetTransaction =
        sendCommand(DOOR_NODE_ADDRESS, COMMAND_CHECK_DOOR_STATUS)

    fun ejectDoor(): CabinetTransaction =
        sendCommand(DOOR_NODE_ADDRESS, COMMAND_EJECT_DOOR)

    fun blueLightOn(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_BLUE_LIGHT_ON)

    fun blueLightOff(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_BLUE_LIGHT_OFF)

    fun redLightOn(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_RED_LIGHT_ON)

    fun redLightOff(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_RED_LIGHT_OFF)

    /** Sends supplier command 0x13 (electromagnet engage). */
    fun engageElectromagnet(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_ELECTROMAGNET_ENGAGE)

    /** Sends supplier command 0x14 (electromagnet release). */
    fun releaseElectromagnet(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_ELECTROMAGNET_RELEASE)

    /**
     * F7G18P key-fob action used by the guided enrolment flow.
     *
     * The cabinet's physical key peg was verified with this Terminal as
     * removable after command 0x13. The supplier document calls the command
     * "electromagnet engage", so keep the generic method above for hardware
     * diagnostics and use this explicit business-action name in enrolment.
     */
    fun unlockKeyFob(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_ELECTROMAGNET_ENGAGE)

    /**
     * F7G18P key-fob action used after a returned fob is detected.
     *
     * The physical key peg was verified as secured after command 0x14. This
     * intentionally follows the field-tested peg behaviour rather than the
     * generic supplier command label.
     */
    fun lockKeyFob(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_ELECTROMAGNET_RELEASE)

    fun readKeyCard(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_READ_KEY_CARD)

    fun checkKeyBolt(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_CHECK_KEY_BOLT)

    fun readCombinedNodeStatus(nodeAddress: Int): CabinetTransaction =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_CHECK_NODE_STATUS)

    private fun sendCommand(nodeAddress: Int, command: Int): CabinetTransaction {
        check(serialPort.isOpen) { "Connect the cabinet before sending a command." }

        val requestFrame = buildRequestFrame(boxAddress, nodeAddress, command)
        var responseFrame: ByteArray? = null
        repeat(MAX_SEND_ATTEMPTS) {
            if (responseFrame != null) return@repeat
            serialPort.clearInputBuffer()
            serialPort.write(requestFrame)
            responseFrame = serialPort.readResponseFrame(RESPONSE_TIMEOUT_MILLIS)
        }

        val confirmedResponseFrame = responseFrame
            ?: throw IllegalStateException(
                "No complete cabinet reply arrived within " + RESPONSE_TIMEOUT_MILLIS +
                        " ms after " + MAX_SEND_ATTEMPTS + " attempts.",
            )
        val response = parseResponse(confirmedResponseFrame)

        if (response.boxAddress != boxAddress || response.nodeAddress != nodeAddress) {
            throw IllegalStateException("Cabinet reply address does not match the requested box/node.")
        }
        if (response.command != command) {
            throw IllegalStateException(
                "Unexpected cabinet reply command 0x" +
                        response.command.toString(16).uppercase(Locale.US).padStart(2, '0') + ".",
            )
        }

        return CabinetTransaction(
            requestFrame = requestFrame,
            responseFrame = confirmedResponseFrame,
            response = response,
        )
    }

    private fun requireKeyNode(nodeAddress: Int): Int {
        require(nodeAddress in 0..MAX_KEY_NODE_ADDRESS) {
            "Raw node address must be from 0 to $MAX_KEY_NODE_ADDRESS."
        }
        return nodeAddress
    }

    private fun buildRequestFrame(
        boxAddress: Int,
        nodeAddress: Int,
        command: Int,
    ): ByteArray {
        val rawPayload = byteArrayOf(
            boxAddress.toByte(),
            nodeAddress.toByte(),
            command.toByte(),
        )
        val rawWithCrc = rawPayload + calculateCrc8(rawPayload).toByte()
        val frame = ArrayList<Byte>(10)
        frame.add(0x02.toByte())
        rawWithCrc.forEach { raw ->
            val value = raw.toInt() and 0xFF
            frame.add((0x30 + (value ushr 4)).toByte())
            frame.add((0x30 + (value and 0x0F)).toByte())
        }
        frame.add(0x04.toByte())
        return frame.toByteArray()
    }

    private fun parseResponse(frame: ByteArray): CabinetResponse {
        if (frame.size < 10 || (frame.first().toInt() and 0xFF) != 0x01 ||
            (frame.last().toInt() and 0xFF) != 0x03
        ) {
            throw IllegalStateException("The cabinet reply frame is invalid.")
        }

        val encoded = frame.copyOfRange(1, frame.lastIndex)
        if (encoded.size % 2 != 0) {
            throw IllegalStateException("The cabinet reply has an invalid encoded payload.")
        }

        val raw = ByteArray(encoded.size / 2)
        raw.indices.forEach { index ->
            val high = (encoded[index * 2].toInt() and 0xFF) - 0x30
            val low = (encoded[index * 2 + 1].toInt() and 0xFF) - 0x30
            if (high !in 0..0x0F || low !in 0..0x0F) {
                throw IllegalStateException("The cabinet reply contains an invalid encoded byte.")
            }
            raw[index] = ((high shl 4) or low).toByte()
        }

        if (raw.size < 4) {
            throw IllegalStateException("The cabinet reply is too short.")
        }

        val receivedCrc = raw.last().toInt() and 0xFF
        val expectedCrc = calculateCrc8(raw.copyOf(raw.size - 1))
        if (receivedCrc != expectedCrc) {
            throw IllegalStateException("Cabinet reply CRC validation failed.")
        }

        return CabinetResponse(
            boxAddress = raw[0].toInt() and 0xFF,
            nodeAddress = raw[1].toInt() and 0xFF,
            command = raw[2].toInt() and 0xFF,
            data = raw.copyOfRange(3, raw.size - 1),
        )
    }

    private fun calculateCrc8(data: ByteArray): Int {
        var crc = 0x00
        data.forEach { item ->
            crc = crc xor (item.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x01) == 0) {
                    crc ushr 1
                } else {
                    (crc ushr 1) xor 0x8C
                }
                crc = crc and 0xFF
            }
        }
        return (crc xor 0x87) and 0xFF
    }
}

data class CabinetResponse(
    val boxAddress: Int,
    val nodeAddress: Int,
    val command: Int,
    val data: ByteArray,
)

data class CabinetTransaction(
    val requestFrame: ByteArray,
    val responseFrame: ByteArray,
    val response: CabinetResponse,
)

internal fun ByteArray.isFourBytesOf(value: Int): Boolean =
    size == 4 && all { (it.toInt() and 0xFF) == value }

internal fun ByteArray.toCompactHex(): String =
    joinToString(separator = "") { byte ->
        String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
    }