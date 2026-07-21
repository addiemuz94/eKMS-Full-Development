package com.ekms.terminal.hardware

/**
 * Android-only boundary for the confirmed cabinet hardware.
 *
 * The implementation will own /dev/ttyS1 (cabinet), /dev/ttyS2 (public NFC)
 * and /dev/ttyS0 (R503 fingerprint). Keeping this interface separate prevents
 * dashboard and access-policy code from sending serial commands directly.
 */
interface CabinetGateway {
    suspend fun readDoorStatus(): CabinetDoorStatus
    suspend fun readNode(nodeAddress: Int): NodeSnapshot
    suspend fun releaseKeyPeg(nodeAddress: Int): HardwareActionResult
    suspend fun engageKeyPeg(nodeAddress: Int): HardwareActionResult
}

data class CabinetDoorStatus(
    val isClosed: Boolean,
    val isEngaged: Boolean,
)

data class NodeSnapshot(
    val nodeAddress: Int,
    val keyPegPresent: Boolean,
    val keyFobUid: String?,
)

data class HardwareActionResult(
    val successful: Boolean,
    val detail: String? = null,
)
