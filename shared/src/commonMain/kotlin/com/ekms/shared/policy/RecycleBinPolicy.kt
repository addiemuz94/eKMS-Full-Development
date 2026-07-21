package com.ekms.shared.policy

import com.ekms.shared.domain.RecordType
import com.ekms.shared.domain.UserRole
import kotlinx.serialization.Serializable

/**
 * Soft-delete policy agreed for eKMS.
 *
 * Records stay recoverable for 60 days (the operational interpretation of
 * "up to two months") unless a Super Admin clears them earlier.
 */
object RecycleBinPolicy {
    const val RETENTION_DAYS: Long = 60
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L

    fun mayAccess(role: UserRole): Boolean = role == UserRole.SUPER_ADMIN

    fun mayPurge(role: UserRole): Boolean = role == UserRole.SUPER_ADMIN

    fun expiresAt(deletedAtEpochMillis: Long): Long =
        deletedAtEpochMillis + RETENTION_DAYS * MILLIS_PER_DAY

    fun isExpired(entry: RecycleBinEntry, nowEpochMillis: Long): Boolean =
        nowEpochMillis >= entry.expiresAtEpochMillis
}

@Serializable
data class RecycleBinEntry(
    val id: String,
    val recordType: RecordType,
    val recordId: String,
    val recordLabel: String,
    val deletedByUserId: String,
    val deletedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val restorePayloadVersion: Long,
)
