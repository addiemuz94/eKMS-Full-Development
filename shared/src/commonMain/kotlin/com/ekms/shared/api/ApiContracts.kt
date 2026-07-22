package com.ekms.shared.api

import com.ekms.shared.domain.AuditEvent
import com.ekms.shared.domain.AccountStatus
import com.ekms.shared.domain.CredentialKind
import com.ekms.shared.domain.RecordType
import com.ekms.shared.domain.TerminalConnectionState
import com.ekms.shared.domain.UserRole
import com.ekms.shared.policy.RecycleBinEntry
import com.ekms.shared.sync.OfflineChange
import com.ekms.shared.sync.SyncConflict
import kotlinx.serialization.Serializable

/**
 * Names and DTOs that the apps and backend will share. Transport and base URL
 * are intentionally not hard-coded at this stage.
 */
object ApiPaths {
    const val AUTH_LOGIN = "/v1/auth/login"
    const val AUTH_REFRESH = "/v1/auth/refresh"
    const val SUPER_ADMIN_DASHBOARD = "/v1/admin/dashboard"
    const val ADMIN_USERS = "/v1/admin/users"
    const val ADMIN_USER_CREDENTIALS = "/v1/admin/users/{userId}/credentials"
    const val ADMIN_SITES = "/v1/admin/sites"
    const val ADMIN_TERMINALS = "/v1/admin/terminals"
    const val ADMIN_KEYS = "/v1/admin/keys"
    const val ADMIN_KEY_SLOTS = "/v1/admin/key-slots"
    const val ADMIN_ACCESS_GRANTS = "/v1/admin/access-grants"
    const val ADMIN_KEY_FOB_ENROLLMENT = "/v1/admin/keys/{keyId}/fob-enrollment"
    const val RECYCLE_BIN = "/v1/admin/recycle-bin"
    const val SYNC_BOOTSTRAP = "/v1/terminal/sync/bootstrap"
    const val SYNC_PUSH = "/v1/terminal/sync/push"
    const val SYNC_CONFLICTS = "/v1/admin/sync-conflicts"
    const val AUDIT_EVENTS = "/v1/audit/events"
}

/** API HANDOVER — Super Admin user management. Values are sent only over authenticated HTTPS. */
@Serializable
data class CreateAdminUserRequest(
    val displayName: String,
    val email: String,
    val role: UserRole,
    val assignedSiteIds: Set<String>,
)

@Serializable
data class UpdateAdminUserRequest(
    val displayName: String,
    val email: String,
    val role: UserRole,
    val assignedSiteIds: Set<String>,
    val expectedRevision: Long,
)

@Serializable
data class UpdateAdminUserAccountStatusRequest(
    val accountStatus: AccountStatus,
    val expectedRevision: Long,
)

@Serializable
data class RequestCredentialEnrollmentRequest(
    val credentialKind: CredentialKind,
    val terminalId: String?,
    val expectedRevision: Long,
)

/** API HANDOVER — Sites & Terminals. All PATCH requests use revision checks. */
@Serializable
data class SiteUpsertRequest(
    val name: String,
    val address: String? = null,
    /** Required for PATCH and must match the current backend revision. */
    val expectedRevision: Long? = null,
)

@Serializable
data class TerminalUpsertRequest(
    val siteId: String,
    val name: String,
    val boxAddress: Int,
    val serialNumber: String? = null,
    val configuredSlotCount: Int,
    val cabinetSerialPort: String? = null,
    val cabinetBaudRate: Int? = null,
    val expectedRevision: Long? = null,
)

@Serializable
data class TerminalStatusResponse(
    val terminalId: String,
    val connectionState: TerminalConnectionState,
    val lastSuccessfulSyncEpochMillis: Long? = null,
    val pendingOfflineChangeCount: Int = 0,
)

/**
 * API HANDOVER — Keys, cabinet slots and access grants.
 *
 * `fobEnrollmentReference` is an opaque reference issued by a protected
 * Terminal enrollment flow. Web and Mobile must never send or receive a raw
 * NFC UID or a biometric template.
 */
@Serializable
data class KeyUpsertRequest(
    val siteId: String,
    val displayName: String,
    val fobEnrollmentReference: String? = null,
    val expectedRevision: Long? = null,
)

/**
 * Response from the Android Terminal-only fob-enrolment endpoint.
 *
 * The request body containing the scanned UID intentionally lives only in the
 * Android Terminal module. This shared contract never carries a raw UID.
 */
@Serializable
data class FobEnrollmentResponse(
    val keyId: String,
    val fobEnrollmentReference: String,
    val replacedExistingEnrollment: Boolean,
    val enrolledAtEpochMillis: Long,
    val auditEventId: String,
)

/** Safe input for a Terminal sync outbox; it contains no raw fob identifier. */
@Serializable
data class FobEnrollmentAuditPayload(
    val keyId: String,
    val terminalId: String,
    val siteId: String,
    val eventType: String,
    val occurredAtEpochMillis: Long,
    val entityType: RecordType = RecordType.KEY,
)

@Serializable
data class KeySlotUpsertRequest(
    val terminalId: String,
    /** Actual protocol key-node address: 1..configuredSlotCount; never door node 0. */
    val nodeAddress: Int,
    /** Null explicitly represents a registered but currently unassigned physical slot. */
    val managedKeyId: String? = null,
    val expectedRevision: Long? = null,
)

@Serializable
data class AccessGrantUpsertRequest(
    val userId: String,
    val siteId: String,
    val keyIds: Set<String>,
    val validFromEpochMillis: Long? = null,
    val validUntilEpochMillis: Long? = null,
    val expectedRevision: Long? = null,
)

@Serializable
data class KeySlotAvailabilityResponse(
    val terminalId: String,
    val configuredSlotCount: Int,
    val assignedNodeAddresses: Set<Int>,
    val lastReportedAtEpochMillis: Long? = null,
)

@Serializable
data class DeletePreflightResponse(
    val allowed: Boolean,
    val blockingReason: String? = null,
    val dependentRecordCount: Int = 0,
)

@Serializable
data class TerminalBootstrapRequest(
    val terminalId: String,
    val lastSuccessfulSyncEpochMillis: Long?,
    val localRevision: Long,
)

@Serializable
data class TerminalBootstrapResponse(
    val serverRevision: Long,
    val issuedAtEpochMillis: Long,
    val changesJson: List<String>,
)

@Serializable
data class TerminalSyncPushRequest(
    val terminalId: String,
    val changes: List<OfflineChange>,
    val auditEvents: List<AuditEvent>,
)

@Serializable
data class TerminalSyncPushResponse(
    val acceptedOperationIds: List<String>,
    val conflicts: List<SyncConflict>,
)

@Serializable
data class RecycleBinListResponse(
    val entries: List<RecycleBinEntry>,
    val serverTimeEpochMillis: Long,
)