package com.ekms.shared.protocol

import kotlinx.datetime.Clock

/**
 * R503-5V optical fingerprint module protocol (0xEF01 header framing, checksum-verified),
 * sent over an abstract [SerialTransport] — mirrors [KeyCabinetLink]'s split between pure
 * command/frame logic here (hardware-free testable via [SerialTransport]/`FakeSerialTransport`)
 * and the real Android-only transport (terminalApp's [AndroidSerialTransport], reused against a
 * second instance opened on `/dev/ttyS0` @ 57,600 8N1 — port and baud confirmed against
 * `../eKMSHardwareTester`'s `FingerprintDiagnosticActivity`, not assumed).
 *
 * Ported from that reference project's `R503FingerprintProtocol.kt`/`SerialPortController.kt`,
 * adapted from JVM-only (`Thread.sleep`/`System.currentTimeMillis`) to this module's
 * Kotlin Multiplatform target (Android + Wasm): every method here is a single blocking
 * request/response exchange bounded purely by [SerialTransport]'s own read timeout, with no
 * artificial sleep — the one place the tester's code slept between polling attempts
 * ("wait for a finger" repeated GET_IMAGE polling) is deliberately NOT ported here; that
 * belongs to the Android-side orchestration layer (terminalApp's `FingerprintHardwareController`,
 * parallel to [KeyCabinetLink]'s caller `CabinetHardwareController`), which already owns
 * coroutine-based polling/delay for the cabinet link and can do the same for this one primitive
 * ([getImage]) without needing platform-specific sleep in shared code.
 *
 * One other deliberate behavioral change from the tester: [readPacket] bounds the *whole*
 * packet (header + tail) under one shared deadline, the same way [KeyCabinetLink.readNodeFrameBytes]
 * already does in this codebase — the tester instead gave each of its three reads (1 byte,
 * 8 bytes, N bytes) an independently-fresh full timeout, which could triple the worst-case wait
 * for one packet. Wire framing/commands/checksums are ported faithfully; this timeout-budgeting
 * detail is intentionally tightened to match this codebase's existing convention.
 *
 * Scope: enrollment + template management only (matches the terminalApp feature this backs —
 * no runtime fingerprint-based login/matching is wired up yet). [autoIdentify]/
 * [verifyTemplateOnce] are ported for completeness and future use but have no caller yet.
 */
class R503FingerprintProtocol(
    private val transport: SerialTransport,
) {

    data class R503Packet(
        val address: Long,
        val packetIdentifier: Int,
        val payload: ByteArray,
    ) {
        val confirmationCode: Int?
            get() = if (packetIdentifier == ACK_PACKET_IDENTIFIER && payload.isNotEmpty()) {
                payload[0].toInt() and 0xFF
            } else {
                null
            }
    }

    data class R503Transaction(
        val requestFrame: ByteArray,
        val responsePacket: R503Packet,
    )

    data class R503SystemParameters(
        val statusRegister: Int,
        val systemIdentifier: Int,
        val libraryCapacity: Int,
        val securityLevel: Int,
        val moduleAddress: Long,
        val packetSizeCode: Int,
        val baudRateMultiplier: Int,
    ) {
        val baudRate: Int get() = baudRateMultiplier * 9_600
    }

    data class R503ProductInformation(
        val moduleModel: String,
        val batchNumber: String,
        val serialNumber: String,
        val hardwareVersion: String,
        val sensorType: String,
        val imageWidth: Int,
        val imageHeight: Int,
        val templateSize: Int,
        val templateCapacity: Int,
    )

    data class R503AutoEnrollProgress(
        val confirmationCode: Int?,
        val step: Int?,
        val reportedTemplateId: Int?,
    )

    data class R503AutoEnrollResult(
        val confirmationCode: Int?,
        val finalStep: Int?,
        val storedTemplateId: Int?,
    )

    data class R503VerificationResult(
        val confirmationCode: Int?,
        val finalStep: Int?,
        val matchedTemplateId: Int?,
        val matchScore: Int?,
    )

    fun handshake(): R503Transaction = sendCommand(COMMAND_HANDSHAKE)

    fun checkSensor(): R503Transaction = sendCommand(COMMAND_CHECK_SENSOR)

    fun readSystemParameters(): R503Transaction = sendCommand(COMMAND_READ_SYSTEM_PARAMETERS)

    fun readProductInformation(): R503Transaction = sendCommand(COMMAND_READ_PRODUCT_INFO)

    fun readTemplateCount(): R503Transaction = sendCommand(COMMAND_TEMPLATE_COUNT)

    fun emptyFingerprintLibrary(): R503Transaction = sendCommand(COMMAND_EMPTY_FINGERPRINT_LIBRARY)

    /** Single-shot finger-presence check (0x01) — callers own any polling/retry cadence. */
    fun getImage(): R503Transaction = sendCommand(COMMAND_GET_IMAGE)

    fun readTemplateIndex(page: Int = 0): R503Transaction {
        require(page in 0..3) { "R503 template-index page must be between 0 and 3." }
        return sendCommand(COMMAND_READ_INDEX_TABLE, byteArrayOf(page.toByte()))
    }

    fun deleteSingleTemplate(templateId: Int): R503Transaction {
        require(templateId in 0..199) { "Template ID must be between 0 and 199." }
        return sendCommand(
            COMMAND_DELETE_TEMPLATE,
            byteArrayOf((templateId ushr 8).toByte(), templateId.toByte(), 0x00, 0x01),
        )
    }

    /**
     * Section-matching the tester's `autoEnroll`: one write, then up to 15 response packets
     * (one per enrollment step — 6 captures + 6 feature extractions + duplicate check + merge +
     * save), each reported via [onProgress]. Auto-assigns the template ID (0xC8 flag) rather
     * than requiring the caller to pick a free slot.
     */
    fun autoEnroll(onProgress: (R503AutoEnrollProgress) -> Unit): R503AutoEnrollResult {
        check(transport.isOpen) { "Connect to /dev/ttyS0 before starting fingerprint enrolment." }

        val parameters = byteArrayOf(AUTO_ASSIGN_TEMPLATE_ID.toByte(), 0x00, 0x00, 0x01, 0x01)
        val requestFrame = buildCommandPacket(COMMAND_AUTO_ENROLL, parameters)
        transport.clearInputBuffer()
        transport.write(requestFrame)

        var lastPacket: R503Packet? = null
        repeat(15) {
            val packet = readPacket(AUTO_ENROLL_PACKET_TIMEOUT_MILLIS)
            lastPacket = packet
            val confirmationCode = packet.confirmationCode
            val step = packet.payload.getOrNull(1)?.toUnsignedInt()
            val reportedTemplateId = packet.payload.getOrNull(2)?.toUnsignedInt()

            onProgress(R503AutoEnrollProgress(confirmationCode, step, reportedTemplateId))

            if (confirmationCode != 0x00 || step == 0x0F) {
                return buildAutoEnrollResult(packet)
            }
        }

        return buildAutoEnrollResult(lastPacket)
    }

    /** Verifies one presented finger against a known template ID (0x32 with a fixed template). */
    fun verifyTemplateOnce(
        templateId: Int,
        securityLevel: Int = 3,
        onProgress: (confirmationCode: Int?, step: Int?) -> Unit = { _, _ -> },
    ): R503VerificationResult {
        require(templateId in 0..199) { "R503 template ID must be between 0 and 199." }
        require(securityLevel in 1..5) { "R503 security level must be between 1 and 5." }
        check(transport.isOpen) { "Connect to /dev/ttyS0 before verifying a fingerprint." }

        val parameters = byteArrayOf(securityLevel.toByte(), templateId.toByte(), 0x01, 0x01, 0x01)
        val requestFrame = buildCommandPacket(COMMAND_AUTO_IDENTIFY, parameters)
        transport.clearInputBuffer()
        transport.write(requestFrame)

        var lastPacket: R503Packet? = null
        repeat(3) { responseIndex ->
            val timeoutMillis = if (responseIndex == 0) {
                AUTO_VERIFY_FIRST_PACKET_TIMEOUT_MILLIS
            } else {
                AUTO_VERIFY_NEXT_PACKET_TIMEOUT_MILLIS
            }
            val packet = readPacket(timeoutMillis)
            lastPacket = packet
            val confirmationCode = packet.confirmationCode
            val step = packet.payload.getOrNull(1)?.toUnsignedInt()
            onProgress(confirmationCode, step)
            if (confirmationCode != 0x00 || step == 0x03) {
                return buildVerificationResult(packet)
            }
        }
        return buildVerificationResult(lastPacket)
    }

    /** Searches the whole library (or a sub-range) for a match, rather than one known template. */
    fun autoIdentify(
        startTemplateId: Int,
        templateCount: Int,
        securityLevel: Int = 3,
    ): R503VerificationResult {
        require(securityLevel in 1..5) { "R503 security level must be between 1 and 5." }
        require(startTemplateId in 0..199) { "R503 start template ID must be between 0 and 199." }
        require(templateCount in 1..200) { "R503 template count must be between 1 and 200." }
        require(startTemplateId + templateCount <= 200) {
            "R503 search range exceeds the 200-slot fingerprint library."
        }
        check(transport.isOpen) { "Connect to /dev/ttyS0 before starting fingerprint verification." }

        val parameters = byteArrayOf(
            securityLevel.toByte(),
            startTemplateId.toByte(),
            templateCount.toByte(),
            0x01,
            0x01,
        )
        val requestFrame = buildCommandPacket(COMMAND_AUTO_IDENTIFY, parameters)
        transport.clearInputBuffer()
        transport.write(requestFrame)

        var lastPacket: R503Packet? = null
        repeat(3) { responseIndex ->
            val timeoutMillis = if (responseIndex == 0) {
                AUTO_IDENTIFY_FIRST_RESPONSE_TIMEOUT_MILLIS
            } else {
                AUTO_IDENTIFY_NEXT_RESPONSE_TIMEOUT_MILLIS
            }
            val packet = readPacket(timeoutMillis)
            lastPacket = packet
            val confirmationCode = packet.confirmationCode
            val step = packet.payload.getOrNull(1)?.toUnsignedInt()
            if (confirmationCode != 0x00 || step == 0x03) {
                return buildVerificationResult(packet)
            }
        }
        return buildVerificationResult(lastPacket)
    }

    fun parseSystemParameters(transaction: R503Transaction): R503SystemParameters {
        val payload = requireSuccessfulAcknowledgement(transaction)
        require(payload.size >= 17) { "R503 system-parameter response is too short." }
        val data = payload.copyOfRange(1, 17)
        return R503SystemParameters(
            statusRegister = readUnsignedWord(data, 0),
            systemIdentifier = readUnsignedWord(data, 2),
            libraryCapacity = readUnsignedWord(data, 4),
            securityLevel = readUnsignedWord(data, 6),
            moduleAddress = readUnsignedDoubleWord(data, 8),
            packetSizeCode = readUnsignedWord(data, 12),
            baudRateMultiplier = readUnsignedWord(data, 14),
        )
    }

    fun parseProductInformation(transaction: R503Transaction): R503ProductInformation {
        val payload = requireSuccessfulAcknowledgement(transaction)
        require(payload.size >= 47) { "R503 product-information response is too short." }
        val data = payload.copyOfRange(1, 47)
        return R503ProductInformation(
            moduleModel = readAscii(data, 0, 16),
            batchNumber = readAscii(data, 16, 4),
            serialNumber = readAscii(data, 20, 8),
            hardwareVersion = "${data[28].toUnsignedInt()}.${data[29].toUnsignedInt()}",
            sensorType = readAscii(data, 30, 8),
            imageWidth = readUnsignedWord(data, 38),
            imageHeight = readUnsignedWord(data, 40),
            templateSize = readUnsignedWord(data, 42),
            templateCapacity = readUnsignedWord(data, 44),
        )
    }

    fun parseTemplateCount(transaction: R503Transaction): Int {
        val payload = requireSuccessfulAcknowledgement(transaction)
        require(payload.size >= 3) { "R503 template-count response is too short." }
        return readUnsignedWord(payload, 1)
    }

    fun parseTemplateIndex(transaction: R503Transaction, page: Int = 0): List<Int> {
        require(page in 0..3) { "R503 template-index page must be between 0 and 3." }
        val payload = requireSuccessfulAcknowledgement(transaction)
        require(payload.size >= 33) { "R503 template-index response is too short." }
        val indexBytes = payload.copyOfRange(1, 33)
        val occupiedTemplateIds = mutableListOf<Int>()
        indexBytes.forEachIndexed { byteIndex, byteValue ->
            val value = byteValue.toUnsignedInt()
            for (bit in 0..7) {
                if ((value and (1 shl bit)) != 0) {
                    occupiedTemplateIds += (page * 256) + (byteIndex * 8) + bit
                }
            }
        }
        return occupiedTemplateIds
    }

    fun requireSuccessfulAcknowledgement(transaction: R503Transaction): ByteArray {
        val packet = transaction.responsePacket
        if (packet.packetIdentifier != ACK_PACKET_IDENTIFIER) {
            throw R503ProtocolException(
                "Expected R503 acknowledgment packet (0x07), received 0x${packet.packetIdentifier.toHexByte()}.",
            )
        }
        val confirmationCode = packet.confirmationCode
            ?: throw R503ProtocolException("R503 acknowledgment has no confirmation code.")
        if (confirmationCode != 0x00) {
            throw R503CommandException(confirmationCode)
        }
        return packet.payload
    }

    private fun buildAutoEnrollResult(finalPacket: R503Packet?): R503AutoEnrollResult {
        val confirmationCode = finalPacket?.confirmationCode
        val payload = finalPacket?.payload
        val finalStep = payload?.getOrNull(1)?.toUnsignedInt()
        val storedTemplateId = payload?.let { data ->
            if (confirmationCode == 0x00 && finalStep == 0x0F && data.size >= 3) data[2].toUnsignedInt() else null
        }
        return R503AutoEnrollResult(confirmationCode, finalStep, storedTemplateId)
    }

    private fun buildVerificationResult(finalPacket: R503Packet?): R503VerificationResult {
        val confirmationCode = finalPacket?.confirmationCode
        val payload = finalPacket?.payload
        val finalStep = payload?.getOrNull(1)?.toUnsignedInt()
        val matchPayload = payload?.takeIf { data ->
            confirmationCode == 0x00 && finalStep == 0x03 && data.size >= 6
        }
        return R503VerificationResult(
            confirmationCode = confirmationCode,
            finalStep = finalStep,
            matchedTemplateId = matchPayload?.let { readUnsignedWord(it, 2) },
            matchScore = matchPayload?.let { readUnsignedWord(it, 4) },
        )
    }

    private fun sendCommand(command: Int, parameters: ByteArray = ByteArray(0)): R503Transaction {
        check(transport.isOpen) { "Connect to /dev/ttyS0 before sending an R503 command." }
        val requestFrame = buildCommandPacket(command, parameters)
        transport.clearInputBuffer()
        transport.write(requestFrame)
        return R503Transaction(requestFrame, readPacket())
    }

    private fun buildCommandPacket(command: Int, parameters: ByteArray): ByteArray {
        val payload = byteArrayOf(command.toByte()) + parameters
        val packetLength = payload.size + 2
        val lengthHigh = (packetLength ushr 8).toByte()
        val lengthLow = packetLength.toByte()

        val framePrefix = byteArrayOf(
            HEADER_HIGH.toByte(),
            HEADER_LOW.toByte(),
            ((DEFAULT_MODULE_ADDRESS shr 24) and 0xFFL).toByte(),
            ((DEFAULT_MODULE_ADDRESS shr 16) and 0xFFL).toByte(),
            ((DEFAULT_MODULE_ADDRESS shr 8) and 0xFFL).toByte(),
            (DEFAULT_MODULE_ADDRESS and 0xFFL).toByte(),
            COMMAND_PACKET_IDENTIFIER.toByte(),
            lengthHigh,
            lengthLow,
        )

        val checksumInput = byteArrayOf(COMMAND_PACKET_IDENTIFIER.toByte(), lengthHigh, lengthLow) + payload
        val checksum = calculateChecksum(checksumInput)

        return framePrefix + payload + byteArrayOf((checksum ushr 8).toByte(), checksum.toByte())
    }

    private fun readPacket(timeoutMillis: Long = RESPONSE_TIMEOUT_MILLIS): R503Packet {
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMillis

        var firstByte = readRequiredByte(deadline)
        var powerOnIndicators = 0
        while (firstByte == POWER_ON_READY_INDICATOR) {
            powerOnIndicators += 1
            if (powerOnIndicators > 4) {
                throw R503ProtocolException("Received repeated R503 power-on indicators without a packet.")
            }
            firstByte = readRequiredByte(deadline)
        }
        if (firstByte != HEADER_HIGH) {
            throw R503ProtocolException("Invalid R503 response header byte: 0x${firstByte.toHexByte()}.")
        }

        val header = ByteArray(HEADER_BYTES)
        header[0] = firstByte.toByte()
        for (i in 1 until HEADER_BYTES) header[i] = readRequiredByte(deadline).toByte()
        if (header[1].toUnsignedInt() != HEADER_LOW) {
            throw R503ProtocolException("Invalid R503 response header.")
        }

        val packetLength = (header[7].toUnsignedInt() shl 8) or header[8].toUnsignedInt()
        if (packetLength !in MIN_PACKET_LENGTH..MAX_PACKET_LENGTH) {
            throw R503ProtocolException("Invalid R503 packet length: $packetLength.")
        }

        val tail = ByteArray(packetLength) { readRequiredByte(deadline).toByte() }
        val payload = tail.copyOfRange(0, tail.size - 2)
        val receivedChecksum = (tail[tail.size - 2].toUnsignedInt() shl 8) or tail[tail.size - 1].toUnsignedInt()
        val checksumInput = byteArrayOf(header[6], header[7], header[8]) + payload
        val expectedChecksum = calculateChecksum(checksumInput)
        if (receivedChecksum != expectedChecksum) {
            throw R503ProtocolException(
                "R503 checksum failed. Expected 0x${expectedChecksum.toHexWord()}, received 0x${receivedChecksum.toHexWord()}.",
            )
        }

        return R503Packet(
            address = readUnsignedDoubleWord(header, 2),
            packetIdentifier = header[6].toUnsignedInt(),
            payload = payload,
        )
    }

    private fun readRequiredByte(deadlineEpochMillis: Long): Int {
        val remaining = deadlineEpochMillis - Clock.System.now().toEpochMilliseconds()
        if (remaining <= 0) throw R503ProtocolException("Timed out waiting for an R503 byte.")
        return transport.readByteOrNull(remaining)
            ?: throw R503ProtocolException("Timed out waiting for an R503 byte.")
    }

    private fun calculateChecksum(bytes: ByteArray): Int =
        bytes.fold(0) { sum, value -> (sum + value.toUnsignedInt()) and 0xFFFF }

    private fun readUnsignedWord(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toUnsignedInt() shl 8) or bytes[offset + 1].toUnsignedInt()

    private fun readUnsignedDoubleWord(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)

    private fun readAscii(bytes: ByteArray, offset: Int, length: Int): String {
        val value = bytes.copyOfRange(offset, offset + length)
        val zeroIndex = value.indexOfFirst { it == 0.toByte() }
        val textBytes = if (zeroIndex >= 0) value.copyOf(zeroIndex) else value
        return textBytes.decodeToString().trim()
    }

    private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF

    private fun Int.toHexByte(): String = (this and 0xFF).toString(16).padStart(2, '0')

    private fun Int.toHexWord(): String = (this and 0xFFFF).toString(16).padStart(4, '0')

    companion object {
        const val DEFAULT_MODULE_ADDRESS = 0xFFFFFFFFL

        /** Confirmed against `../eKMSHardwareTester`'s FingerprintDiagnosticActivity. */
        const val PORT_PATH = "/dev/ttyS0"
        const val BAUD_RATE = 57_600

        private const val HEADER_HIGH = 0xEF
        private const val HEADER_LOW = 0x01
        private const val COMMAND_PACKET_IDENTIFIER = 0x01
        private const val ACK_PACKET_IDENTIFIER = 0x07
        private const val POWER_ON_READY_INDICATOR = 0x55

        private const val COMMAND_GET_IMAGE = 0x01
        private const val COMMAND_DELETE_TEMPLATE = 0x0C
        private const val COMMAND_EMPTY_FINGERPRINT_LIBRARY = 0x0D
        private const val COMMAND_READ_SYSTEM_PARAMETERS = 0x0F
        private const val COMMAND_TEMPLATE_COUNT = 0x1D
        private const val COMMAND_READ_INDEX_TABLE = 0x1F
        private const val COMMAND_AUTO_ENROLL = 0x31
        private const val COMMAND_AUTO_IDENTIFY = 0x32
        private const val COMMAND_CHECK_SENSOR = 0x36
        private const val COMMAND_READ_PRODUCT_INFO = 0x3C
        private const val COMMAND_HANDSHAKE = 0x40

        private const val RESPONSE_TIMEOUT_MILLIS = 1_500L
        private const val AUTO_ENROLL_PACKET_TIMEOUT_MILLIS = 15_000L
        private const val AUTO_VERIFY_FIRST_PACKET_TIMEOUT_MILLIS = 12_000L
        private const val AUTO_VERIFY_NEXT_PACKET_TIMEOUT_MILLIS = 2_000L
        private const val AUTO_IDENTIFY_FIRST_RESPONSE_TIMEOUT_MILLIS = 12_000L
        private const val AUTO_IDENTIFY_NEXT_RESPONSE_TIMEOUT_MILLIS = 2_000L

        private const val AUTO_ASSIGN_TEMPLATE_ID = 0xC8
        private const val HEADER_BYTES = 9
        private const val MIN_PACKET_LENGTH = 3
        private const val MAX_PACKET_LENGTH = 258

        fun confirmationCodeDescription(code: Int): String = when (code) {
            0x00 -> "Success"
            0x01 -> "Packet receive error"
            0x02 -> "No finger detected"
            0x03 -> "Fingerprint enrolment failed"
            0x06 -> "Fingerprint image too disordered"
            0x07 -> "Fingerprint image quality too poor"
            0x08 -> "Fingerprint does not match"
            0x09 -> "Fingerprint not found"
            0x0A -> "Template merge failed"
            0x0B -> "Template ID out of range"
            0x0C -> "Template invalid or cannot be read"
            0x10 -> "Template deletion failed"
            0x11 -> "Fingerprint library clear failed"
            0x1F -> "Fingerprint library full"
            0x20 -> "Incorrect module address"
            0x22 -> "Fingerprint template is empty"
            0x24 -> "Fingerprint library is empty"
            0x26 -> "Operation timed out"
            0x27 -> "Fingerprint already exists"
            0x29 -> "Sensor hardware error"
            0xFC -> "Unsupported command"
            0xFD -> "Hardware error"
            0xFE -> "Command execution failed"
            else -> "Unknown module response"
        }
    }
}

class R503ProtocolException(message: String) : Exception(message)

class R503CommandException(val confirmationCode: Int) : Exception(
    "R503 returned 0x${(confirmationCode and 0xFF).toString(16).padStart(2, '0')} " +
        "(${R503FingerprintProtocol.confirmationCodeDescription(confirmationCode)}).",
)
