package com.ekms.shared.protocol

/** Parses a space-separated hex byte string (as written in the protocol doc) into a ByteArray. */
internal fun hexBytes(hex: String): ByteArray =
    hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
