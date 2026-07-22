package com.ekms.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Worked examples are from the protocol doc, section 3.1. */
class SplitNibbleCodecTest {
    @Test
    fun `encodes documented conversion examples`() {
        assertEquals(0x30 to 0x31, SplitNibbleCodec.encodeByte(0x01))
        assertEquals(0x30 to 0x3F, SplitNibbleCodec.encodeByte(0x0F))
        assertEquals(0x31 to 0x32, SplitNibbleCodec.encodeByte(0x12))
        assertEquals(0x37 to 0x3F, SplitNibbleCodec.encodeByte(0x7F))
        assertEquals(0x3A to 0x3A, SplitNibbleCodec.encodeByte(0xAA))
        assertEquals(0x3F to 0x3F, SplitNibbleCodec.encodeByte(0xFF))
    }

    @Test
    fun `encodes a byte array as two bytes per raw byte`() {
        val encoded = SplitNibbleCodec.encode(hexBytes("01 0F 12 7F AA FF"))
        assertEquals(
            listOf(hexBytes("30 31"), hexBytes("30 3F"), hexBytes("31 32"), hexBytes("37 3F"), hexBytes("3A 3A"), hexBytes("3F 3F"))
                .fold(byteArrayOf()) { acc, bytes -> acc + bytes }
                .toList(),
            encoded.toList(),
        )
    }

    @Test
    fun `decodes documented conversion examples`() {
        assertEquals(0x01, SplitNibbleCodec.decodeByte(0x30, 0x31))
        assertEquals(0x0F, SplitNibbleCodec.decodeByte(0x30, 0x3F))
        assertEquals(0x12, SplitNibbleCodec.decodeByte(0x31, 0x32))
        assertEquals(0x7F, SplitNibbleCodec.decodeByte(0x37, 0x3F))
        assertEquals(0xAA, SplitNibbleCodec.decodeByte(0x3A, 0x3A))
        assertEquals(0xFF, SplitNibbleCodec.decodeByte(0x3F, 0x3F))
    }

    @Test
    fun `encode then decode round-trips for every byte value`() {
        for (value in 0..0xFF) {
            val (high, low) = SplitNibbleCodec.encodeByte(value)
            assertEquals(value, SplitNibbleCodec.decodeByte(high, low))
        }
    }

    @Test
    fun `decode round-trips a full byte array`() {
        val raw = hexBytes("01 01 15 AA BB CC DD BF")
        val decoded = SplitNibbleCodec.decode(SplitNibbleCodec.encode(raw))
        assertEquals(raw.toList(), decoded?.toList())
    }

    @Test
    fun `decode discards an odd-length body`() {
        assertNull(SplitNibbleCodec.decode(hexBytes("30 31 30")))
    }

    @Test
    fun `decode discards a byte outside the 0x30-0x3F encoded range`() {
        assertNull(SplitNibbleCodec.decode(hexBytes("30 41")))
    }
}
