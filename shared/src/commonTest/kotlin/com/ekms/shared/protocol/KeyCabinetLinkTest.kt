package com.ekms.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [KeyCabinetLink] end-to-end against a [FakeSerialTransport] — no
 * physical device attached, matching the worked examples in
 * docs/Key Cabinet Communication Protocol.md.
 */
class KeyCabinetLinkTest {
    private fun ackReply(nodeAddress: Int, command: Int, data: ByteArray = ByteArray(0)): ByteArray =
        KeyCabinetFrameCodec.buildNodeFrame(
            KeyCabinetFrame(boxAddress = 1, nodeAddress = nodeAddress, command = command, data = data),
        )

    @Test
    fun `blueLightOn sends the documented frame and parses its reply`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(ackReply(nodeAddress = 1, command = 0x11))
        val link = KeyCabinetLink(transport, boxAddress = 1)

        val reply = link.blueLightOn(1)

        assertEquals(1, transport.writtenFrames.size)
        assertEquals(hexBytes("02 30 31 30 31 31 31 32 3B 04").toList(), transport.writtenFrames.single().toList())
        assertEquals(KeyCabinetFrame(1, 1, 0x11), reply)
    }

    @Test
    fun `checkDoorStatus addresses node 0 and parses the documented door-engaged reply`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(hexBytes("01 30 31 30 30 32 32 30 30 30 30 30 30 30 30 3C 31 03"))
        val link = KeyCabinetLink(transport, boxAddress = 1)

        val reply = link.checkDoorStatus()

        assertEquals(hexBytes("02 30 31 30 30 32 32 3B 33 04").toList(), transport.writtenFrames.single().toList())
        assertEquals(KeyCabinetFrame(1, 0, 0x22, hexBytes("00 00 00 00")), reply)
    }

    @Test
    fun `ejectDoor addresses node 0 and parses the documented acknowledgement`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(hexBytes("01 30 31 30 30 32 33 3E 3D 03"))
        val link = KeyCabinetLink(transport, boxAddress = 1)

        val reply = link.ejectDoor()

        assertEquals(hexBytes("02 30 31 30 30 32 33 3E 3D 04").toList(), transport.writtenFrames.single().toList())
        assertEquals(KeyCabinetFrame(1, 0, 0x23), reply)
    }

    @Test
    fun `readCard returns the documented card number`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(hexBytes("01 30 31 30 31 31 35 3A 3A 3B 3B 3C 3C 3D 3D 3B 3F 03"))
        val link = KeyCabinetLink(transport, boxAddress = 1)

        val reply = link.readCard(1)

        assertEquals(hexBytes("02 30 31 30 31 31 35 34 3A 04").toList(), transport.writtenFrames.single().toList())
        assertEquals(hexBytes("AA BB CC DD").toList(), reply.data.toList())
    }

    @Test
    fun `testMicroSwitch returns the documented bolt-present and no-bolt replies`() {
        val boltPresentTransport = FakeSerialTransport()
        boltPresentTransport.enqueueResponse(hexBytes("01 30 31 30 31 31 36 30 30 30 30 30 30 30 30 36 3D 03"))
        assertEquals(
            hexBytes("00 00 00 00").toList(),
            KeyCabinetLink(boltPresentTransport, boxAddress = 1).testMicroSwitch(1).data.toList(),
        )

        val noBoltTransport = FakeSerialTransport()
        noBoltTransport.enqueueResponse(hexBytes("01 30 31 30 31 31 36 3F 3F 3F 3F 3F 3F 3F 3F 3E 30 03"))
        assertEquals(
            hexBytes("FF FF FF FF").toList(),
            KeyCabinetLink(noBoltTransport, boxAddress = 1).testMicroSwitch(1).data.toList(),
        )
    }

    @Test
    fun `testMicroSwitchAndCard returns the documented card-present reply`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(hexBytes("01 30 31 30 31 31 37 3A 3A 3B 3B 3C 3C 3D 3D 33 3C 03"))
        val link = KeyCabinetLink(transport, boxAddress = 1)

        val reply = link.testMicroSwitchAndCard(1)

        assertEquals(hexBytes("02 30 31 30 31 31 37 3F 36 04").toList(), transport.writtenFrames.single().toList())
        assertEquals(hexBytes("AA BB CC DD").toList(), reply.data.toList())
    }

    @Test
    fun `retries up to three times and succeeds once a valid reply arrives`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(null) // attempt 1: no response
        transport.enqueueResponse(null) // attempt 2: no response
        transport.enqueueResponse(ackReply(nodeAddress = 5, command = 0x11)) // attempt 3: success
        val link = KeyCabinetLink(transport, boxAddress = 1)

        val reply = link.blueLightOn(5)

        assertEquals(3, transport.writtenFrames.size)
        assertTrue(transport.writtenFrames.all { it.toList() == transport.writtenFrames.first().toList() })
        assertEquals(KeyCabinetFrame(1, 5, 0x11), reply)
    }

    @Test
    fun `reports failure once and throws after exhausting every retry attempt`() {
        val transport = FakeSerialTransport()
        repeat(3) { transport.enqueueResponse(null) }
        var failureCount = 0
        var lastMessage: String? = null
        val link = KeyCabinetLink(
            transport,
            boxAddress = 1,
            onCommandFailure = { _, _, message -> failureCount++; lastMessage = message },
        )

        assertFailsWith<KeyCabinetCommandException> { link.blueLightOn(5) }
        assertEquals(3, transport.writtenFrames.size)
        assertEquals(1, failureCount)
        assertTrue(lastMessage!!.contains("0x11"))
    }

    @Test
    fun `retries when the reply's CRC does not verify`() {
        val transport = FakeSerialTransport()
        // Same shape as a valid Blue Light On reply but with a deliberately wrong CRC byte pair.
        transport.enqueueResponse(hexBytes("01 30 31 30 31 31 31 32 3C 03"))
        transport.enqueueResponse(ackReply(nodeAddress = 1, command = 0x11))
        val link = KeyCabinetLink(transport, boxAddress = 1)

        val reply = link.blueLightOn(1)

        assertEquals(2, transport.writtenFrames.size)
        assertEquals(KeyCabinetFrame(1, 1, 0x11), reply)
    }

    @Test
    fun `retries when the reply addresses the wrong node`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(ackReply(nodeAddress = 2, command = 0x11)) // wrong node
        transport.enqueueResponse(ackReply(nodeAddress = 1, command = 0x11)) // correct
        val link = KeyCabinetLink(transport, boxAddress = 1)

        val reply = link.blueLightOn(1)

        assertEquals(2, transport.writtenFrames.size)
        assertEquals(1, reply.nodeAddress)
    }

    @Test
    fun `rejects a node address above the documented 127 maximum`() {
        val link = KeyCabinetLink(FakeSerialTransport(), boxAddress = 1)
        assertFailsWith<IllegalArgumentException> { link.blueLightOn(128) }
    }

    @Test
    fun `engaging a second node while one is already engaged is blocked and never transmitted`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(ackReply(nodeAddress = 1, command = 0x13))
        val link = KeyCabinetLink(transport, boxAddress = 1)

        link.engageElectromagnet(1)
        assertEquals(1, link.engagedNodeAddress)
        val framesBeforeBlockedAttempt = transport.writtenFrames.size

        val error = assertFailsWith<ElectromagnetConcurrencyException> { link.engageElectromagnet(2) }
        assertEquals(1, error.engagedNodeAddress)
        assertEquals(2, error.requestedNodeAddress)

        // Safety-critical: the blocked engage must never reach the wire.
        assertEquals(framesBeforeBlockedAttempt, transport.writtenFrames.size)
        assertEquals(1, link.engagedNodeAddress)
    }

    @Test
    fun `two retrieval attempts in quick succession - the second is rejected, not queued or run concurrently`() {
        // Models CabinetHardwareController.releaseKeyForPickup for two different
        // keys at two different nodes, called back-to-back before the first
        // retrieval's node is ever secured again — e.g. two operator taps in
        // quick succession, or two near-simultaneous take-key requests.
        val transport = FakeSerialTransport()
        transport.enqueueResponse(ackReply(nodeAddress = 10, command = 0x13)) // key A's retrieval succeeds
        val link = KeyCabinetLink(transport, boxAddress = 1)

        // First retrieval: node 10's electromagnet engages normally.
        link.engageElectromagnet(10)
        assertEquals(1, transport.writtenFrames.size)
        assertEquals(10, link.engagedNodeAddress)

        // Second retrieval arrives immediately after, for a different key at a
        // different node, before node 10 has been secured again.
        val framesBeforeSecondAttempt = transport.writtenFrames.size
        val rejection = assertFailsWith<ElectromagnetConcurrencyException> { link.engageElectromagnet(20) }

        // Rejected outright — not silently queued to run once node 10 frees up,
        // and critically: node 20's command never reached the wire, so there is
        // no window where both electromagnets could have been drawing current.
        assertEquals(10, rejection.engagedNodeAddress)
        assertEquals(20, rejection.requestedNodeAddress)
        assertEquals(framesBeforeSecondAttempt, transport.writtenFrames.size)
        assertEquals(10, link.engagedNodeAddress)

        // Only after node 10 is genuinely secured again does node 20 become available.
        transport.enqueueResponse(ackReply(nodeAddress = 10, command = 0x14))
        transport.enqueueResponse(ackReply(nodeAddress = 20, command = 0x13))
        link.releaseElectromagnet(10)
        link.engageElectromagnet(20)
        assertEquals(20, link.engagedNodeAddress)
    }

    @Test
    fun `releasing the engaged node clears the guard and allows engaging a different node`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(ackReply(nodeAddress = 1, command = 0x13)) // engage node 1
        transport.enqueueResponse(ackReply(nodeAddress = 1, command = 0x14)) // release node 1
        transport.enqueueResponse(ackReply(nodeAddress = 2, command = 0x13)) // engage node 2
        val link = KeyCabinetLink(transport, boxAddress = 1)

        link.engageElectromagnet(1)
        link.releaseElectromagnet(1)
        assertNull(link.engagedNodeAddress)

        link.engageElectromagnet(2)
        assertEquals(2, link.engagedNodeAddress)
    }

    @Test
    fun `re-engaging the already-engaged node is allowed`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(ackReply(nodeAddress = 1, command = 0x13))
        transport.enqueueResponse(ackReply(nodeAddress = 1, command = 0x13))
        val link = KeyCabinetLink(transport, boxAddress = 1)

        link.engageElectromagnet(1)
        link.engageElectromagnet(1) // same node again: must not throw

        assertEquals(1, link.engagedNodeAddress)
        assertEquals(2, transport.writtenFrames.size)
    }

    @Test
    fun `releasing a node that was never engaged still sends the command but leaves the guard untouched`() {
        val transport = FakeSerialTransport()
        transport.enqueueResponse(ackReply(nodeAddress = 1, command = 0x13)) // engage node 1
        transport.enqueueResponse(ackReply(nodeAddress = 9, command = 0x14)) // release unrelated node 9
        val link = KeyCabinetLink(transport, boxAddress = 1)

        link.engageElectromagnet(1)
        link.releaseElectromagnet(9)

        assertEquals(1, link.engagedNodeAddress)
    }

    @Test
    fun `throws when a command is sent before the transport is open`() {
        val transport = FakeSerialTransport().apply { isOpen = false }
        val link = KeyCabinetLink(transport, boxAddress = 1)
        assertFailsWith<IllegalStateException> { link.blueLightOn(1) }
    }
}
