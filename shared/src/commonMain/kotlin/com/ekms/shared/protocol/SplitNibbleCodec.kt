package com.ekms.shared.protocol

/**
 * Key Cabinet Communication Protocol, section 3.1 — split-nibble encoding.
 *
 * Every transmitted byte except the frame's start/end byte is split into its
 * high and low nibble, each converted to an ASCII byte by adding `0x30`.
 * Encoded bytes therefore always fall in `0x30..0x3F` (ASCII `'0'`–`'?'`).
 *
 * This is pure byte math with no serial/Android dependency, so it lives in
 * `shared` and is unit-testable without a physical device.
 */
object SplitNibbleCodec {
    private const val NIBBLE_OFFSET = 0x30
    const val ENCODED_BYTE_MIN = 0x30
    const val ENCODED_BYTE_MAX = 0x3F

    /** Encodes one raw byte (0-255) into its (high, low) encoded byte pair. */
    fun encodeByte(rawValue: Int): Pair<Int, Int> {
        require(rawValue in 0..0xFF) { "Raw byte value must be 0-255." }
        val high = NIBBLE_OFFSET + ((rawValue ushr 4) and 0x0F)
        val low = NIBBLE_OFFSET + (rawValue and 0x0F)
        return high to low
    }

    /** Encodes every byte of [raw] into two encoded bytes each. */
    fun encode(raw: ByteArray): ByteArray {
        val result = ByteArray(raw.size * 2)
        raw.forEachIndexed { index, byte ->
            val (high, low) = encodeByte(byte.toInt() and 0xFF)
            result[index * 2] = high.toByte()
            result[index * 2 + 1] = low.toByte()
        }
        return result
    }

    /** Decodes one (high, low) encoded byte pair back into its raw byte value (0-255). */
    fun decodeByte(high: Int, low: Int): Int {
        val highNibble = (high - NIBBLE_OFFSET) and 0x0F
        val lowNibble = (low - NIBBLE_OFFSET) and 0x0F
        return (highNibble shl 4) or lowNibble
    }

    /**
     * Decodes [encoded] back into raw bytes. Returns null (per section 7.5 —
     * an invalid frame is discarded, not partially trusted) if the length is
     * odd or any byte falls outside `0x30..0x3F`.
     */
    fun decode(encoded: ByteArray): ByteArray? {
        if (encoded.size % 2 != 0) return null
        if (encoded.any { (it.toInt() and 0xFF) !in ENCODED_BYTE_MIN..ENCODED_BYTE_MAX }) return null

        val result = ByteArray(encoded.size / 2)
        for (index in result.indices) {
            val high = encoded[index * 2].toInt() and 0xFF
            val low = encoded[index * 2 + 1].toInt() and 0xFF
            result[index] = decodeByte(high, low).toByte()
        }
        return result
    }
}
