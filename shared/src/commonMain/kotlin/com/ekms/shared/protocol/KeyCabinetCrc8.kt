package com.ekms.shared.protocol

/**
 * Key Cabinet Communication Protocol, section 6 — CRC8 checksum.
 *
 * Computed over the raw (unsplit) Box Address + Node Address + Command +
 * Data Area, before split-nibble encoding and excluding the start/end bytes
 * and the CRC8 field itself. Initial value `0x00`, polynomial `0x8C`
 * (reversed), final XOR `0x87`, no input/output reflection.
 */
object KeyCabinetCrc8 {
    private const val INITIAL_VALUE = 0x00
    private const val POLYNOMIAL = 0x8C
    private const val FINAL_XOR = 0x87

    fun calculate(data: ByteArray): Int {
        var crc = INITIAL_VALUE
        data.forEach { byte ->
            crc = crc xor (byte.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x01) == 0) {
                    crc ushr 1
                } else {
                    (crc ushr 1) xor POLYNOMIAL
                }
                crc = crc and 0xFF
            }
        }
        return (crc xor FINAL_XOR) and 0xFF
    }
}
