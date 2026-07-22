package com.ekms.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every transmission frame here is a worked example from the protocol doc, section 5. */
class KeyCabinetFrameCodecTest {
    @Test
    fun `builds the documented host frame for every no-data command`() {
        val cases = listOf(
            0x11 to "02 30 31 30 31 31 31 32 3B 04", // Blue Light On
            0x12 to "02 30 31 30 31 31 32 3C 39 04", // Blue Light Off
            0x19 to "02 30 31 30 31 31 39 3E 39 04", // Red Light On
            0x1A to "02 30 31 30 31 31 3A 30 3B 04", // Red Light Off
            0x13 to "02 30 31 30 31 31 33 39 37 04", // Electromagnet Engage
            // See KeyCabinetCrc8Test for why this uses CRC 0x14 (encoded "31 34"),
            // not the vendor doc's stated 0x15 ("31 35") for this one worked example.
            0x14 to "02 30 31 30 31 31 34 31 34 04", // Electromagnet Release
            0x15 to "02 30 31 30 31 31 35 34 3A 04", // Read Card
            0x16 to "02 30 31 30 31 31 36 3A 38 04", // Test Micro Switch
            0x17 to "02 30 31 30 31 31 37 3F 36 04", // Micro Switch + Card
        )

        cases.forEach { (command, expectedFrameHex) ->
            val frame = KeyCabinetFrame(boxAddress = 1, nodeAddress = 1, command = command)
            assertEquals(
                hexBytes(expectedFrameHex).toList(),
                KeyCabinetFrameCodec.buildHostFrame(frame).toList(),
                "Frame mismatch for command 0x${command.toString(16)}",
            )
        }
    }

    @Test
    fun `builds the documented door-command host frames at node 0`() {
        val checkDoor = KeyCabinetFrame(boxAddress = 1, nodeAddress = 0, command = 0x22)
        assertEquals(
            hexBytes("02 30 31 30 30 32 32 3B 33 04").toList(),
            KeyCabinetFrameCodec.buildHostFrame(checkDoor).toList(),
        )

        val ejectDoor = KeyCabinetFrame(boxAddress = 1, nodeAddress = 0, command = 0x23)
        assertEquals(
            hexBytes("02 30 31 30 30 32 33 3E 3D 04").toList(),
            KeyCabinetFrameCodec.buildHostFrame(ejectDoor).toList(),
        )
    }

    @Test
    fun `parses the documented Read Card reply`() {
        val reply = KeyCabinetFrameCodec.parseNodeFrame(
            hexBytes("01 30 31 30 31 31 35 3A 3A 3B 3B 3C 3C 3D 3D 3B 3F 03"),
        )
        assertEquals(KeyCabinetFrame(1, 1, 0x15, hexBytes("AA BB CC DD")), reply)
    }

    @Test
    fun `parses the documented Test Micro Switch replies`() {
        val boltPresent = KeyCabinetFrameCodec.parseNodeFrame(
            hexBytes("01 30 31 30 31 31 36 30 30 30 30 30 30 30 30 36 3D 03"),
        )
        assertEquals(KeyCabinetFrame(1, 1, 0x16, hexBytes("00 00 00 00")), boltPresent)

        val noBolt = KeyCabinetFrameCodec.parseNodeFrame(
            hexBytes("01 30 31 30 31 31 36 3F 3F 3F 3F 3F 3F 3F 3F 3E 30 03"),
        )
        assertEquals(KeyCabinetFrame(1, 1, 0x16, hexBytes("FF FF FF FF")), noBolt)
    }

    @Test
    fun `parses the documented Micro Switch plus Card replies`() {
        val cardPresent = KeyCabinetFrameCodec.parseNodeFrame(
            hexBytes("01 30 31 30 31 31 37 3A 3A 3B 3B 3C 3C 3D 3D 33 3C 03"),
        )
        assertEquals(KeyCabinetFrame(1, 1, 0x17, hexBytes("AA BB CC DD")), cardPresent)

        val noCardBoltPresent = KeyCabinetFrameCodec.parseNodeFrame(
            hexBytes("01 30 31 30 31 31 37 30 30 30 30 30 30 30 30 3A 30 03"),
        )
        assertEquals(KeyCabinetFrame(1, 1, 0x17, hexBytes("00 00 00 00")), noCardBoltPresent)

        val noCardNoBolt = KeyCabinetFrameCodec.parseNodeFrame(
            hexBytes("01 30 31 30 31 31 37 3F 3F 3F 3F 3F 3F 3F 3F 32 3D 03"),
        )
        assertEquals(KeyCabinetFrame(1, 1, 0x17, hexBytes("FF FF FF FF")), noCardNoBolt)
    }

    @Test
    fun `parses the documented Check Door Status replies at node 0`() {
        val engaged = KeyCabinetFrameCodec.parseNodeFrame(
            hexBytes("01 30 31 30 30 32 32 30 30 30 30 30 30 30 30 3C 31 03"),
        )
        assertEquals(KeyCabinetFrame(1, 0, 0x22, hexBytes("00 00 00 00")), engaged)

        val closed = KeyCabinetFrameCodec.parseNodeFrame(
            hexBytes("01 30 31 30 30 32 32 3F 3F 3F 3F 3F 3F 3F 3F 34 3C 03"),
        )
        assertEquals(KeyCabinetFrame(1, 0, 0x22, hexBytes("FF FF FF FF")), closed)
    }

    @Test
    fun `parses the documented Eject Door acknowledgement`() {
        val ack = KeyCabinetFrameCodec.parseNodeFrame(hexBytes("01 30 31 30 30 32 33 3E 3D 03"))
        assertEquals(KeyCabinetFrame(1, 0, 0x23), ack)
    }

    @Test
    fun `build then parse round-trips for a frame with a data area`() {
        val original = KeyCabinetFrame(boxAddress = 3, nodeAddress = 42, command = 0x15, data = hexBytes("12 34 56 78"))
        val parsed = KeyCabinetFrameCodec.parseNodeFrame(KeyCabinetFrameCodec.buildNodeFrame(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `discards a frame with the wrong start byte`() {
        // Correct Blue Light On reply body, but a host start byte (0x02) instead of a node reply's 0x01.
        assertNull(KeyCabinetFrameCodec.parseNodeFrame(hexBytes("02 30 31 30 31 31 31 32 3B 03")))
    }

    @Test
    fun `discards a frame with the wrong end byte`() {
        assertNull(KeyCabinetFrameCodec.parseNodeFrame(hexBytes("01 30 31 30 31 31 31 32 3B 04")))
    }

    @Test
    fun `discards a frame with a corrupted CRC8`() {
        // Last two encoded bytes (the CRC) are flipped from the documented 32 3B to 32 3C.
        assertNull(KeyCabinetFrameCodec.parseNodeFrame(hexBytes("01 30 31 30 31 31 31 32 3C 03")))
    }

    @Test
    fun `discards a frame whose encoded body is too short to contain box, node, command and CRC8`() {
        assertNull(KeyCabinetFrameCodec.parseNodeFrame(hexBytes("01 30 31 03")))
    }
}
