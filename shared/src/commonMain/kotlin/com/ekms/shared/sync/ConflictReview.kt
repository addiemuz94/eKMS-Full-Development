package com.ekms.shared.sync

import com.ekms.shared.domain.RecordType
import com.ekms.shared.domain.UserRole
import kotlinx.serialization.Serializable

/** Offline edits never silently overwrite another admin's newer edit. */
@Serializable
data class OfflineChange(
    val operationId: String,
    val entityType: RecordType,
    val entityId: String,
    val baseRevision: Long,
    val submittedAtEpochMillis: Long,
    val submittedByUserId: String,
    val payloadJson: String,
)

@Serializable
data class SyncConflict(
    val id: String,
    val entityType: RecordType,
    val entityId: String,
    val serverRevision: Long,
    val localChange: OfflineChange,
    val createdAtEpochMillis: Long,
    val requiresSuperAdminReview: Boolean = true,
    val resolution: ConflictResolution? = null,
)

@Serializable
enum class ConflictResolutionStrategy {
    KEEP_SERVER,
    KEEP_TERMINAL_CHANGE,
    MERGE_MANUALLY,
}

@Serializable
data class ConflictResolution(
    val strategy: ConflictResolutionStrategy,
    val resolvedByUserId: String,
    val resolvedAtEpochMillis: Long,
    val mergedPayloadJson: String? = null,
)

object ConflictReviewPolicy {
    /** Only a Super Admin may resolve a conflict created by an offline terminal edit. */
    fun mayResolve(role: UserRole): Boolean = role == UserRole.SUPER_ADMIN
}
