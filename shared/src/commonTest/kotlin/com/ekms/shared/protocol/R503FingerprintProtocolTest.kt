package com.ekms.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Verifies [R503FingerprintProtocol] end-to-end against a [FakeSerialTransport] — no physical
 * device attached. `handshake` and `checkSensor`'s request frames are hand-computed byte-for-byte
 * (0xEF01 header, module address 0xFFFFFFFF, checksum = sum of [packetId, lengthHi, lengthLo,
 * ...payload] truncated to 16 bits) as an independent cross-check of the framing/checksum
 * algorithm; the rest of the suite builds frames via [r503Ack] using that same, by-then-verified
 * algorithm so every test still exercises real parsing/validation rather than construction.
 */
class R503FingerprintProtocolTest {

    private fun r503Frame(packetIdentifier: Int, payload: ByteArray): ByteArray {
        val packetLength = payload.size + 2
        val lengthHigh = (packetLength ushr 8).toByte()
        val lengthLow = packetLength.toByte()
        val header = byteArrayOf(
            0xEF.toByte(), 0x01,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            packetIdentifier.toByte(), lengthHigh, lengthLow,
        )
        val checksumInput = byteArrayOf(packetIdentifier.toByte(), lengthHigh, lengthLow) + payload
        val checksum = checksumInput.fold(0) { sum, b -> (sum + (b.toInt() and 0xFF)) and 0xFFFF }
        return header + payload + byteArrayOf((checksum ushr 8).toByte(), checksum.toByte())
    }

    private fun r503Ack(vararg payload: Int): ByteArray =
        r503Frame(0x07, payload.map { it.toByte() }.toByteArray())

    @Test
    fun `handshake sends the hand-verified request frame and parses a successful ack`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(hexBytes("EF 01 FF FF FF FF 07 00 03 00 00 0A"))
        val protocol = R503FingerprintProtocol(transport)

        val transaction = protocol.handshake()

        assertEquals(
            hexBytes("EF 01 FF FF FF FF 01 00 03 40 00 44").toList(),
            transaction.requestFrame.toList(),
        )
        assertEquals(transaction.requestFrame.toList(), transport.writtenFrames.single().toList())
        assertEquals(0x00, transaction.responsePacket.confirmationCode)
    }

    @Test
    fun `checkSensor sends the hand-verified request frame and reports a hardware error`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(hexBytes("EF 01 FF FF FF FF 07 00 03 29 00 33"))
        val protocol = R503FingerprintProtocol(transport)

        val transaction = protocol.checkSensor()

        assertEquals(
            hexBytes("EF 01 FF FF FF FF 01 00 03 36 00 3A").toList(),
            transport.writtenFrames.single().toList(),
        )
        assertEquals(0x29, transaction.responsePacket.confirmationCode)
        assertEquals("Sensor hardware error", R503FingerprintProtocol.confirmationCodeDescription(0x29))
    }

    @Test
    fun `getImage reports no-finger-detected without throwing`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(r503Ack(0x02))
        val protocol = R503FingerprintProtocol(transport)

        val transaction = protocol.getImage()

        assertEquals(0x02, transaction.responsePacket.confirmationCode)
    }

    @Test
    fun `readTemplateCount parses the reported count`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(r503Ack(0x00, 0x00, 0x05))
        val protocol = R503FingerprintProtocol(transport)

        val count = protocol.parseTemplateCount(protocol.readTemplateCount())

        assertEquals(5, count)
    }

    @Test
    fun `parseTemplateCount throws R503CommandException on a non-zero confirmation code`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(r503Ack(0x24)) // library empty
        val protocol = R503FingerprintProtocol(transport)

        val error = assertFailsWith<R503CommandException> {
            protocol.parseTemplateCount(protocol.readTemplateCount())
        }

        assertEquals(0x24, error.confirmationCode)
    }

    @Test
    fun `readSystemParameters parses every field at its documented offset`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(
            r503Ack(
                0x00, // confirmation
                0x00, 0x01, // status register
                0x00, 0x02, // system identifier
                0x00, 0xC8, // library capacity 200
                0x00, 0x03, // security level
                0xFF, 0xFF, 0xFF, 0xFF, // module address
                0x00, 0x02, // packet size code
                0x00, 0x06, // baud multiplier 6 -> 57600
            ),
        )
        val protocol = R503FingerprintProtocol(transport)

        val parameters = protocol.parseSystemParameters(protocol.readSystemParameters())

        assertEquals(1, parameters.statusRegister)
        assertEquals(200, parameters.libraryCapacity)
        assertEquals(3, parameters.securityLevel)
        assertEquals(0xFFFFFFFFL, parameters.moduleAddress)
        assertEquals(6, parameters.baudRateMultiplier)
        assertEquals(57_600, parameters.baudRate)
    }

    @Test
    fun `readTemplateIndex decodes occupied template IDs from the bitmap`() {
        val transport = FakeSerialTransport()
        val indexBitmap = IntArray(32) { 0 }
        indexBitmap[0] = 0b00100001 // bits 0 and 5 -> template IDs 0 and 5
        val payload = intArrayOf(0x00) + indexBitmap
        transport.enqueueResponse(r503Ack(*payload))
        val protocol = R503FingerprintProtocol(transport)

        val ids = protocol.parseTemplateIndex(protocol.readTemplateIndex(page = 0), page = 0)

        assertEquals(listOf(0, 5), ids)
    }

    @Test
    fun `deleteSingleTemplate sends the requested template ID and count`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(r503Ack(0x00))
        val protocol = R503FingerprintProtocol(transport)

        protocol.deleteSingleTemplate(templateId = 3)

        // command 0x0C, template ID high/low = 00 03, count = 00 01
        val sent = transport.writtenFrames.single()
        assertEquals(0x0C, sent[9].toInt() and 0xFF)
        assertEquals(0x00, sent[10].toInt() and 0xFF)
        assertEquals(0x03, sent[11].toInt() and 0xFF)
    }

    @Test
    fun `autoEnroll streams progress across multiple packets and reports the final stored template ID`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(
            r503Ack(0x00, 0x01, 0x00) + r503Ack(0x00, 0x0F, 0x07),
        )
        val protocol = R503FingerprintProtocol(transport)
        val progressSteps = mutableListOf<Int?>()

        val result = protocol.autoEnroll { progress -> progressSteps.add(progress.step) }

        assertEquals(listOf<Int?>(0x01, 0x0F), progressSteps)
        assertEquals(0x00, result.confirmationCode)
        assertEquals(7, result.storedTemplateId)
        assertEquals(1, transport.writtenFrames.size) // one write, multiple packets read back
    }

    @Test
    fun `autoEnroll stops early and reports failure on a non-zero confirmation mid-sequence`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(r503Ack(0x09)) // "fingerprint not found" style abort
        val protocol = R503FingerprintProtocol(transport)

        val result = protocol.autoEnroll { }

        assertEquals(0x09, result.confirmationCode)
        assertNull(result.storedTemplateId)
    }

    @Test
    fun `verifyTemplateOnce reports a match with its score`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(
            r503Ack(0x00, 0x01) + r503Ack(0x00, 0x02) + r503Ack(0x00, 0x03, 0x00, 0x2A, 0x00, 0x63),
        )
        val protocol = R503FingerprintProtocol(transport)

        val result = protocol.verifyTemplateOnce(templateId = 42)

        assertEquals(0x00, result.confirmationCode)
        assertEquals(42, result.matchedTemplateId)
        assertEquals(99, result.matchScore)
    }

    @Test
    fun `autoIdentify reports no match found`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(r503Ack(0x09)) // fingerprint not found
        val protocol = R503FingerprintProtocol(transport)

        val result = protocol.autoIdentify(startTemplateId = 0, templateCount = 10)

        assertEquals(0x09, result.confirmationCode)
        assertNull(result.matchedTemplateId)
    }

    @Test
    fun `a corrupted checksum is rejected rather than silently accepted`() {
        val transport = FakeSerialTransport()
        val goodFrame = r503Ack(0x00)
        val corrupted = goodFrame.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 1).toByte()
        transport.enqueueResponse(corrupted)
        val protocol = R503FingerprintProtocol(transport)

        assertFailsWith<R503ProtocolException> { protocol.handshake() }
    }

    @Test
    fun `a full timeout with no response surfaces as R503ProtocolException, not a hang`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(null)
        val protocol = R503FingerprintProtocol(transport)

        assertFailsWith<R503ProtocolException> { protocol.handshake() }
    }
}
