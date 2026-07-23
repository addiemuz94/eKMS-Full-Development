package com.ekms.shared.api

import com.ekms.shared.domain.AuditEvent
import com.ekms.shared.domain.AccountStatus
import com.ekms.shared.domain.CredentialKind
import com.ekms.shared.domain.RecordType
import com.ekms.shared.domain.TerminalConnectionState
import com.ekms.shared.domain.UserRole
import com.ekms.shared.policy.RecycleBinEntry
import com.ekms.shared.sync.ConflictResolutionStrategy
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
    const val ADMIN_EVENT_DEFINITIONS = "/v1/admin/event-definitions"
    const val ADMIN_SCHEDULES = "/v1/admin/schedules"
    const val ADMIN_PERSONNEL_GROUPS = "/v1/admin/personnel-groups"
    const val ADMIN_KEY_GROUPS = "/v1/admin/key-groups"
    const val ADMIN_MULTI_AUTH_RULES = "/v1/admin/multi-authentication-rules"
    const val ADMIN_APPOINTMENTS = "/v1/admin/appointments"
    const val ADMIN_APPOINTMENT_REASONS = "/v1/admin/appointment-reasons"
    const val ADMIN_APPOINTMENT_PERMISSIONS = "/v1/admin/appointment-permissions"
    const val REPORTS_KEY_OPERATIONS = "/v1/reports/key-operations"
    const val REPORTS_SYSTEM_LOGS = "/v1/reports/system-operation-logs"
    const val REPORTS_EQUIPMENT_LOGS = "/v1/reports/equipment-operation-logs"
    const val REPORTS_EXPORTS = "/v1/reports/exports"
    const val RECYCLE_BIN = "/v1/admin/recycle-bin"
    const val SYNC_BOOTSTRAP = "/v1/terminal/sync/bootstrap"
    const val SYNC_PUSH = "/v1/terminal/sync/push"
    const val TERMINAL_DATA_READ = "/v1/terminal/sync/read"
    const val TERMINAL_DATA_DOWNLOAD = "/v1/terminal/sync/download"
    const val SYNC_CONFLICTS = "/v1/admin/sync-conflicts"
    const val AUDIT_EVENTS = "/v1/audit/events"
}

@Serializable
enum class AuthClientType {
    WEB,
    MOBILE,
    TERMINAL,
}

@Serializable
data class LoginRequest(
    val identifier: String,
    val password: String,
    val clientType: AuthClientType = AuthClientType.WEB,
    val deviceId: String? = null,
)

@Serializable
data class AuthUserProfile(
    val id: String,
    val displayName: String,
    val email: String,
    val role: UserRole,
    val assignedSiteIds: Set<String> = emptySet(),
    val accountStatus: AccountStatus = AccountStatus.ACTIVE,
    val revision: Long = 1,
)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val profile: AuthUserProfile,
    val role: UserRole,
    val permittedSiteIds: Set<String> = emptySet(),
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
)

@Serializable
data class SiteDto(
    val id: String,
    val name: String,
    val address: String? = null,
    val revision: Long,
)

@Serializable
data class TerminalDto(
    val id: String,
    val siteId: String,
    val name: String,
    val boxAddress: Int,
    val serialNumber: String? = null,
    val configuredSlotCount: Int = 0,
    val cabinetSerialPort: String? = null,
    val cabinetBaudRate: Int? = null,
    val connectionState: TerminalConnectionState = TerminalConnectionState.UNKNOWN,
    val revision: Long,
)

@Serializable
data class UserDto(
    val id: String,
    val displayName: String,
    val email: String,
    val role: UserRole,
    val assignedSiteIds: Set<String> = emptySet(),
    val accountStatus: AccountStatus = AccountStatus.ACTIVE,
    val revision: Long,
)

@Serializable
data class KeyDto(
    val id: String,
    val siteId: String,
    val displayName: String,
    val fobEnrollmentReference: String? = null,
    val revision: Long,
)

@Serializable
data class KeySlotDto(
    val id: String,
    val terminalId: String,
    val nodeAddress: Int,
    val managedKeyId: String? = null,
    val revision: Long,
)

@Serializable
data class AccessGrantDto(
    val id: String,
    val userId: String,
    val siteId: String,
    val keyIds: Set<String> = emptySet(),
    val validFromEpochMillis: Long? = null,
    val validUntilEpochMillis: Long? = null,
    val revision: Long,
)

@Serializable
data class SiteListResponse(val items: List<SiteDto> = emptyList())

@Serializable
data class TerminalListResponse(val items: List<TerminalDto> = emptyList())

@Serializable
data class UserListResponse(val items: List<UserDto> = emptyList())

@Serializable
data class KeyListResponse(val items: List<KeyDto> = emptyList())

@Serializable
data class KeySlotListResponse(val items: List<KeySlotDto> = emptyList())

@Serializable
data class AccessGrantListResponse(val items: List<AccessGrantDto> = emptyList())

/** API HANDOVER — Super Admin user management. Values are sent only over authenticated HTTPS. */
@Serializable
data class CreateAdminUserRequest(
    val displayName: String,
    val email: String,
    val role: UserRole,
    val assignedSiteIds: Set<String>,
    val password: String? = null,
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
    val terminalId: String? = null,
    val expectedRevision: Long? = null,
    val note: String? = null,
)

@Serializable
data class CredentialStatusDto(
    val id: String,
    val userId: String,
    val credentialKind: CredentialKind,
    val enrollmentStatus: String,
    val terminalId: String? = null,
    val note: String? = null,
    val revision: Long,
)

@Serializable
data class CredentialStatusListResponse(
    val items: List<CredentialStatusDto> = emptyList(),
)

@Serializable
data class RecycleBinRestoreRequest(
    val recordType: RecordType,
    val recordId: String,
    val expectedRevision: Long? = null,
)

@Serializable
data class RecycleBinPurgeRequest(
    val recordType: RecordType,
    val recordId: String,
)

@Serializable
data class AuditEventListResponse(
    val items: List<AuditEvent> = emptyList(),
)

@Serializable
data class SyncConflictListResponse(
    val items: List<SyncConflict> = emptyList(),
)

@Serializable
data class ResolveSyncConflictRequest(
    val strategy: ConflictResolutionStrategy,
    val mergedPayloadJson: String? = null,
)

@Serializable
data class TerminalSyncAckResponse(
    val ok: Boolean = true,
    val terminalId: String,
    val message: String? = null,
    val serverRevision: Long? = null,
    val issuedAtEpochMillis: Long? = null,
    val requestedAtEpochMillis: Long? = null,
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

/* ---------- Phase 4 portal workflow contracts ---------- */

@Serializable
enum class ScheduleFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
}

@Serializable
enum class AppointmentStatusDto {
    PENDING,
    APPROVED,
    REJECTED,
}

@Serializable
enum class ReportExportFormat {
    PDF,
    EXCEL,
}

@Serializable
enum class ReportExportKind {
    KEY_OPERATIONS,
    SYSTEM_OPERATION_LOGS,
    EQUIPMENT_OPERATION_LOGS,
}

@Serializable
data class EventDefinitionDto(
    val id: String,
    val siteId: String,
    val name: String,
    val eventNumber: String,
    val requirement: String? = null,
    val revision: Long,
)

@Serializable
data class EventDefinitionUpsertRequest(
    val siteId: String,
    val name: String,
    val eventNumber: String,
    val requirement: String? = null,
    val expectedRevision: Long? = null,
)

@Serializable
data class EventDefinitionListResponse(val items: List<EventDefinitionDto> = emptyList())

@Serializable
data class ScheduleDto(
    val id: String,
    val siteId: String,
    val name: String,
    val frequency: ScheduleFrequency,
    val timeWindowLabel: String,
    val revision: Long,
)

@Serializable
data class ScheduleUpsertRequest(
    val siteId: String,
    val name: String,
    val frequency: ScheduleFrequency,
    val timeWindowLabel: String,
    val expectedRevision: Long? = null,
)

@Serializable
data class ScheduleListResponse(val items: List<ScheduleDto> = emptyList())

@Serializable
data class NamedGroupDto(
    val id: String,
    val siteId: String,
    val name: String,
    val code: String,
    val revision: Long,
)

@Serializable
data class NamedGroupUpsertRequest(
    val siteId: String,
    val name: String,
    val code: String,
    val expectedRevision: Long? = null,
)

@Serializable
data class NamedGroupListResponse(val items: List<NamedGroupDto> = emptyList())

@Serializable
data class MultiAuthRuleDto(
    val id: String,
    val siteId: String,
    val primaryPersonnelGroupId: String,
    val assistantGroupOneId: String? = null,
    val assistantGroupTwoId: String? = null,
    val keyGroupId: String,
    val revision: Long,
)

@Serializable
data class MultiAuthRuleUpsertRequest(
    val siteId: String,
    val primaryPersonnelGroupId: String,
    val assistantGroupOneId: String? = null,
    val assistantGroupTwoId: String? = null,
    val keyGroupId: String,
    val expectedRevision: Long? = null,
)

@Serializable
data class MultiAuthRuleListResponse(val items: List<MultiAuthRuleDto> = emptyList())

@Serializable
data class AppointmentReasonDto(
    val id: String,
    val siteId: String,
    val name: String,
    val active: Boolean = true,
    val revision: Long,
)

@Serializable
data class AppointmentReasonUpsertRequest(
    val siteId: String,
    val name: String,
    val active: Boolean = true,
    val expectedRevision: Long? = null,
)

@Serializable
data class AppointmentReasonListResponse(val items: List<AppointmentReasonDto> = emptyList())

@Serializable
data class AppointmentDto(
    val id: String,
    val siteId: String,
    val terminalId: String,
    val userId: String,
    val reasonId: String? = null,
    val reasonLabel: String? = null,
    val keyIds: Set<String> = emptySet(),
    val pickupWindowLabel: String,
    val validFromEpochMillis: Long? = null,
    val validUntilEpochMillis: Long? = null,
    val status: AppointmentStatusDto = AppointmentStatusDto.PENDING,
    val reviewerUserId: String? = null,
    val reviewDetail: String? = null,
    val revision: Long,
)

@Serializable
data class AppointmentCreateRequest(
    val siteId: String,
    val terminalId: String,
    val userId: String,
    val reasonId: String? = null,
    val reasonLabel: String? = null,
    val keyIds: Set<String> = emptySet(),
    val pickupWindowLabel: String,
    val validFromEpochMillis: Long? = null,
    val validUntilEpochMillis: Long? = null,
)

@Serializable
data class AppointmentReviewRequest(
    val status: AppointmentStatusDto,
    val reviewDetail: String? = null,
    val expectedRevision: Long,
)

@Serializable
data class AppointmentPermissionsPatchRequest(
    val keyIds: Set<String>,
    val expectedRevision: Long,
)

@Serializable
data class AppointmentListResponse(val items: List<AppointmentDto> = emptyList())

@Serializable
data class ReportFilterRequest(
    val siteId: String? = null,
    val terminalId: String? = null,
    val userId: String? = null,
    val keyId: String? = null,
    val fromEpochMillis: Long? = null,
    val untilEpochMillis: Long? = null,
    val limit: Int = 100,
)

@Serializable
data class KeyOperationReportRow(
    val id: String,
    val occurredAtEpochMillis: Long,
    val eventType: String,
    val terminalId: String? = null,
    val siteId: String? = null,
    val actorUserId: String? = null,
    val entityId: String? = null,
    val detail: String? = null,
)

@Serializable
data class KeyOperationReportResponse(val items: List<KeyOperationReportRow> = emptyList())

@Serializable
data class ReportExportRequest(
    val kind: ReportExportKind,
    val format: ReportExportFormat,
    val filter: ReportFilterRequest = ReportFilterRequest(),
)

@Serializable
data class ReportExportResponse(
    val jobId: String,
    val kind: ReportExportKind,
    val format: ReportExportFormat,
    val status: String,
    val createdAtEpochMillis: Long,
    val downloadPath: String? = null,
    val rowCount: Int = 0,
)