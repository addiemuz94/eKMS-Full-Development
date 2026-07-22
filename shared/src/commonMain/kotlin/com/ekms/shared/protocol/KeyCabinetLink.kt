package com.ekms.shared.protocol

import kotlinx.datetime.Clock

/**
 * Key Cabinet Communication Protocol, section 8.1 — the full node command
 * set, sent over an abstract [SerialTransport] using the frame codec built
 * in the shared protocol layer. This class owns the request/reply exchange
 * for one already-connected cabinet: frame assembly, the section 7.4
 * timeout/retry policy, and the section 10.4 one-electromagnet-at-a-time
 * safety invariant.
 *
 * Threading: this class assumes its methods are called from a single
 * caller at a time — true for terminalApp's real usage (which already
 * serializes every cabinet command on one background executor before
 * reaching this class) and true by construction on Wasm. It does not add
 * its own cross-thread lock on top of that; the electromagnet guard below
 * is a logical safety check, not a concurrency primitive.
 */
class KeyCabinetLink(
    private val transport: SerialTransport,
    private val boxAddress: Int,
    private val onCommandFailure: (nodeAddress: Int, command: Int, message: String) -> Unit = { _, _, _ -> },
) {
    /** The node currently holding an engaged (0x13) electromagnet, if any. */
    var engagedNodeAddress: Int? = null
        private set

    init {
        require(boxAddress in 1..255) { "Box Address must be from 1 to 255." }
    }

    fun checkDoorStatus(): KeyCabinetFrame = sendCommand(DOOR_NODE_ADDRESS, COMMAND_CHECK_DOOR_STATUS)

    fun ejectDoor(): KeyCabinetFrame = sendCommand(DOOR_NODE_ADDRESS, COMMAND_EJECT_DOOR)

    fun blueLightOn(nodeAddress: Int): KeyCabinetFrame = sendCommand(requireKeyNode(nodeAddress), COMMAND_BLUE_LIGHT_ON)

    fun blueLightOff(nodeAddress: Int): KeyCabinetFrame = sendCommand(requireKeyNode(nodeAddress), COMMAND_BLUE_LIGHT_OFF)

    fun redLightOn(nodeAddress: Int): KeyCabinetFrame = sendCommand(requireKeyNode(nodeAddress), COMMAND_RED_LIGHT_ON)

    fun redLightOff(nodeAddress: Int): KeyCabinetFrame = sendCommand(requireKeyNode(nodeAddress), COMMAND_RED_LIGHT_OFF)

    fun readCard(nodeAddress: Int): KeyCabinetFrame = sendCommand(requireKeyNode(nodeAddress), COMMAND_READ_CARD)

    fun testMicroSwitch(nodeAddress: Int): KeyCabinetFrame =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_TEST_MICRO_SWITCH)

    fun testMicroSwitchAndCard(nodeAddress: Int): KeyCabinetFrame =
        sendCommand(requireKeyNode(nodeAddress), COMMAND_MICRO_SWITCH_AND_CARD)

    /**
     * Section 10.4: only one electromagnet may be engaged across the whole
     * cabinet at a time — engaging a second one before the first is
     * released risks hardware failure from insufficient current. Throws
     * [ElectromagnetConcurrencyException] instead of sending 0x13 if a
     * different node is already engaged. Re-engaging the same node that is
     * already engaged is allowed (idempotent — no new current draw).
     */
    fun engageElectromagnet(nodeAddress: Int): KeyCabinetFrame {
        val validNode = requireKeyNode(nodeAddress)
        val currentlyEngaged = engagedNodeAddress
        if (currentlyEngaged != null && currentlyEngaged != validNode) {
            throw ElectromagnetConcurrencyException(currentlyEngaged, validNode)
        }
        val frame = sendCommand(validNode, COMMAND_MAGNET_ENGAGE)
        engagedNodeAddress = validNode
        return frame
    }

    /** Section 10.4 counterpart to [engageElectromagnet]; always allowed, and clears the guard. */
    fun releaseElectromagnet(nodeAddress: Int): KeyCabinetFrame {
        val validNode = requireKeyNode(nodeAddress)
        val frame = sendCommand(validNode, COMMAND_MAGNET_RELEASE)
        if (engagedNodeAddress == validNode) {
            engagedNodeAddress = null
        }
        return frame
    }

    private fun requireKeyNode(nodeAddress: Int): Int {
        require(nodeAddress in 0..MAX_KEY_NODE_ADDRESS) {
            "Raw node address must be from 0 to $MAX_KEY_NODE_ADDRESS."
        }
        return nodeAddress
    }

    /**
     * Section 7.4/7.5: 500 ms per attempt, up to [MAX_SEND_ATTEMPTS] attempts
     * total. A timed-out read, a frame the codec discards (bad CRC, bad
     * start/end byte), or a reply whose box/node/command doesn't match the
     * request are all treated as a failed attempt and retried; only after
     * every attempt fails is a failure reported (once, not per attempt) and
     * an exception thrown.
     */
    private fun sendCommand(nodeAddress: Int, command: Int): KeyCabinetFrame {
        check(transport.isOpen) { "Connect the cabinet before sending a command." }
        val requestFrame = KeyCabinetFrameCodec.buildHostFrame(KeyCabinetFrame(boxAddress, nodeAddress, command))

        repeat(MAX_SEND_ATTEMPTS) {
            transport.clearInputBuffer()
            transport.write(requestFrame)
            val deadline = Clock.System.now().toEpochMilliseconds() + RESPONSE_TIMEOUT_MILLIS
            val replyBytes = readNodeFrameBytes(deadline)
            val reply = replyBytes?.let { KeyCabinetFrameCodec.parseNodeFrame(it) }
            if (reply != null && reply.boxAddress == boxAddress && reply.nodeAddress == nodeAddress && reply.command == command) {
                return reply
            }
        }

        val message = "No valid cabinet reply for command 0x${command.toString(16)} at node $nodeAddress " +
                "after $MAX_SEND_ATTEMPTS attempts."
        onCommandFailure(nodeAddress, command, message)
        throw KeyCabinetCommandException(message)
    }

    private fun readNodeFrameBytes(deadlineEpochMillis: Long): ByteArray? {
        val bytes = ArrayList<Byte>()
        var started = false
        while (true) {
            val remaining = deadlineEpochMillis - Clock.System.now().toEpochMilliseconds()
            if (remaining <= 0) return null
            val next = transport.readByteOrNull(remaining) ?: return null

            if (!started) {
                if (next == KeyCabinetFrameCodec.NODE_START_BYTE) {
                    bytes.add(next.toByte())
                    started = true
                }
                continue
            }

            bytes.add(next.toByte())
            if (next == KeyCabinetFrameCodec.NODE_END_BYTE) return bytes.toByteArray()
            if (bytes.size > MAX_FRAME_LENGTH) return null
        }
    }

    companion object {
        const val DOOR_NODE_ADDRESS = 0

        /** Per docs/Key Cabinet Communication Protocol.md §7.1: key nodes are 1-127. */
        const val MAX_KEY_NODE_ADDRESS = 127

        private const val COMMAND_BLUE_LIGHT_ON = 0x11
        private const val COMMAND_BLUE_LIGHT_OFF = 0x12
        private const val COMMAND_MAGNET_ENGAGE = 0x13
        private const val COMMAND_MAGNET_RELEASE = 0x14
        private const val COMMAND_READ_CARD = 0x15
        private const val COMMAND_TEST_MICRO_SWITCH = 0x16
        private const val COMMAND_MICRO_SWITCH_AND_CARD = 0x17
        private const val COMMAND_RED_LIGHT_ON = 0x19
        private const val COMMAND_RED_LIGHT_OFF = 0x1A
        private const val COMMAND_CHECK_DOOR_STATUS = 0x22
        private const val COMMAND_EJECT_DOOR = 0x23

        /** Per docs/Key Cabinet Communication Protocol.md §7.4. */
        private const val RESPONSE_TIMEOUT_MILLIS = 500L
        private const val MAX_SEND_ATTEMPTS = 3
        private const val MAX_FRAME_LENGTH = 128
    }
}

/** Section 10.4: thrown instead of sending 0x13 when a different node's electromagnet is already engaged. */
class ElectromagnetConcurrencyException(val engagedNodeAddress: Int, val requestedNodeAddress: Int) :
    IllegalStateException(
        "Cannot engage the electromagnet at node $requestedNodeAddress: node $engagedNodeAddress is " +
                "still engaged. Only one electromagnet may be engaged at a time (protocol doc section 10.4) " +
                "to avoid insufficient current.",
    )

/** A cabinet command failed after every retry attempt (section 7.4/7.5). */
class KeyCabinetCommandException(message: String) : IllegalStateException(message)
