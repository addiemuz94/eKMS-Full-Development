package com.ekms.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/** Every raw payload/CRC8 pair here is a worked example from the protocol doc, section 5. */
class KeyCabinetCrc8Test {
    @Test
    fun `matches documented CRC8 values for every worked example`() {
        val cases = listOf(
            "01 01 11" to 0x2B, // Blue Light On
            "01 01 12" to 0xC9, // Blue Light Off
            "01 01 19" to 0xE9, // Red Light On
            "01 01 1A" to 0x0B, // Red Light Off
            "01 01 13" to 0x97, // Electromagnet Engage
            // The vendor doc's worked example states 0x15 for this one row, but that
            // is inconsistent with its own documented CRC8 algorithm (initial 0x00,
            // poly 0x8C, final XOR 0x87) applied to "01 01 14" — hand-traced bit by
            // bit twice, both times giving 0x14. Every other one of the doc's 18
            // worked examples validates correctly against that same algorithm, so
            // this looks like a transcription error in that single example rather
            // than an inconsistency in the algorithm itself. Flagged for the vendor;
            // using the algorithm-derived value here since it's the one every other
            // case confirms.
            "01 01 14" to 0x14, // Electromagnet Release
            "01 01 15" to 0x4A, // Read Card (host send)
            "01 01 15 AA BB CC DD" to 0xBF, // Read Card (node return, card=0xAABBCCDD)
            "01 01 16" to 0xA8, // Test Micro Switch (host send)
            "01 01 16 00 00 00 00" to 0x6D, // Test Micro Switch (bolt present)
            "01 01 16 FF FF FF FF" to 0xE0, // Test Micro Switch (no bolt)
            "01 01 17" to 0xF6, // Micro Switch + Card (host send)
            "01 01 17 AA BB CC DD" to 0x3C, // Micro Switch + Card (card present)
            "01 01 17 00 00 00 00" to 0xA0, // Micro Switch + Card (no card, bolt present)
            "01 01 17 FF FF FF FF" to 0x2D, // Micro Switch + Card (no card, no bolt)
            "01 00 22" to 0xB3, // Check Door Status (host send)
            "01 00 22 00 00 00 00" to 0xC1, // Check Door Status (door engaged)
            "01 00 22 FF FF FF FF" to 0x4C, // Check Door Status (door closed)
            "01 00 23" to 0xED, // Eject Door
        )

        cases.forEach { (rawHex, expectedCrc) ->
            assertEquals(
                expectedCrc,
                KeyCabinetCrc8.calculate(hexBytes(rawHex)),
                "CRC8 mismatch for raw data $rawHex",
            )
        }
    }
}
