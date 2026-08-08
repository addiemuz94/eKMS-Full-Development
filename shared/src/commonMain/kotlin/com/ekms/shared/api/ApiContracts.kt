package com.ekms.shared.api

import com.ekms.shared.domain.AuditEvent
import com.ekms.shared.domain.AccessGrant
import com.ekms.shared.domain.AccountStatus
import com.ekms.shared.domain.AdminUser
import com.ekms.shared.domain.CredentialKind
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.ManagedKey
import com.ekms.shared.domain.RecordType
import com.ekms.shared.domain.Terminal
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
    /** Unauthenticated — the whole point is to hand a fresh terminal its first tokens. See TerminalPairWithCodeRequest. */
    const val TERMINAL_PAIR_WITH_CODE = "/v1/terminal/pair-with-code"
    /** Super Admin only. {id} is the backend terminal UUID. Returns RegeneratePairingCodeResponse (plaintext code shown once). */
    const val ADMIN_TERMINAL_PAIRING_CODE = "/v1/admin/terminals/{id}/pairing-code"
    const val SUPER_ADMIN_DASHBOARD = "/v1/admin/dashboard"
    const val ADMIN_USERS = "/v1/admin/users"
    const val ADMIN_USER_CREDENTIALS = "/v1/admin/users/{userId}/credentials"
    const val ADMIN_USER_CREDENTIALS_COMPLETE = "/v1/admin/users/{userId}/credentials/complete"
    const val ADMIN_USER_CREDENTIALS_REVOKE = "/v1/admin/users/{userId}/credentials/revoke"
    const val ADMIN_SITES = "/v1/admin/sites"
    const val ADMIN_TERMINALS = "/v1/admin/terminals"
    /** Super Admin. {id} is terminals.id. GET/PATCH cabinet behavioral settings. */
    const val ADMIN_TERMINAL_CABINET_SETTINGS = "/v1/admin/terminals/{id}/cabinet-settings"
    const val ADMIN_KEYS = "/v1/admin/keys"
    /** TERMINAL_DEVICE-only (see auth.js's allowlist). {id} is managed_keys.id. Reports an
     * opaque fob enrollment reference for a key already assigned a KeySlot — never a raw NFC
     * UID. See [FobEnrollmentCompleteRequest]/[FobEnrollmentResponse]. */
    const val ADMIN_KEY_FOB_ENROLLMENT_COMPLETE = "/v1/admin/keys/{id}/fob-enrollment/complete"
    const val ADMIN_KEY_SLOTS = "/v1/admin/key-slots"
    const val ADMIN_ACCESS_GRANTS = "/v1/admin/access-grants"
    const val ADMIN_KEY_CHECKOUTS = "/v1/admin/key-checkouts"
    /** Super Admin. {id} is sites.id. GET/PATCH per-site office hours (open/close time + timezone). */
    const val ADMIN_SITE_OFFICE_HOURS = "/v1/admin/sites/{id}/office-hours"
    /** Still live — backs terminalApp's deployed Phase 7 `TerminalVendorPasskeyScreen`. Kept
     * unchanged; [ADMIN_KEY_ACCESS_REQUESTS] below is an additive, more general mechanism for
     * mobileApp going forward, not a replacement for this one — both coexist. */
    const val ADMIN_VENDOR_PASSKEY_REQUESTS = "/v1/admin/vendor-passkey-requests"
    const val ADMIN_VENDOR_PASSKEY_REQUEST_APPROVE = "/v1/admin/vendor-passkey-requests/{id}/approve"
    const val ADMIN_VENDOR_PASSKEY_REQUEST_REJECT = "/v1/admin/vendor-passkey-requests/{id}/reject"
    /** Super Admin only. Regions (migration 009) — a geographic grouping ABOVE Site, used only to
     * route a [KeyAccessRequestDto] to the right Regional Admin. See [RegionDto]. */
    const val ADMIN_REGIONS = "/v1/admin/regions"
    /** Additive, more general mechanism alongside [ADMIN_VENDOR_PASSKEY_REQUESTS] (migration
     * 009) — Technician now gets passkey access too, and approval routes via Region instead of
     * Site. See [KeyAccessRequestDto]. Not a rename/replacement of the vendor-only route. */
    const val ADMIN_KEY_ACCESS_REQUESTS = "/v1/admin/key-access-requests"
    const val ADMIN_KEY_ACCESS_REQUEST_APPROVE = "/v1/admin/key-access-requests/{id}/approve"
    const val ADMIN_KEY_ACCESS_REQUEST_REJECT = "/v1/admin/key-access-requests/{id}/reject"
    const val ADMIN_KEY_ACCESS_REQUEST_REVOKE = "/v1/admin/key-access-requests/{id}/revoke"
    /** Requester-facing self-cancel (Technician/Vendor only — the request's own requester, not
     * an admin; admins use [ADMIN_KEY_ACCESS_REQUEST_REVOKE] instead). Terminal, no-revive state
     * (see [KeyAccessRequestStatus.CANCELLED]) — reaching the cabinet again needs a brand-new
     * request. Valid from PENDING/PENDING_PIC/PENDING_RA/APPROVED only. */
    const val ADMIN_KEY_ACCESS_REQUEST_CANCEL = "/v1/admin/key-access-requests/{id}/cancel"
    const val ADMIN_KEY_ACCESS_REQUEST_PIC_APPROVE = "/v1/admin/key-access-requests/{id}/pic-approve"
    const val ADMIN_KEY_ACCESS_PIC_INBOX = "/v1/admin/key-access-requests/pic-inbox"
    const val ADMIN_KEY_ACCESS_SITE_PICS = "/v1/admin/key-access-requests/site-pics/{siteId}"
    /** Fetches one attached document's raw bytes (response Content-Type is the stored
     * contentType). Read access mirrors [ADMIN_KEY_ACCESS_REQUESTS]/{id} — see
     * keyAccessRequests.js's assertMayReadRequest: the request's own requester, its assigned PIC
     * (Technician, while PENDING_PIC/PENDING_RA/APPROVED), or a Regional/Super Admin scoped to
     * the request's site. Metadata for the same documents is embedded on
     * [KeyAccessRequestDto.documents] — this route is bytes-only. */
    const val ADMIN_KEY_ACCESS_REQUEST_DOCUMENT_DOWNLOAD =
        "/v1/admin/key-access-requests/{id}/documents/{documentId}"
    const val ADMIN_MOBILE_PUSH_TOKENS = "/v1/admin/mobile-push-tokens"
    /** Resolves a single site's [SiteKeyAccessPolicyDto.maxKeyAccessDurationMinutes] ceiling — a
     * narrow, purpose-built read so a requester's mobile form can bound its duration picker.
     * Was Region-derived (via regions.js's Super-Admin-only table) before the "regional confusion"
     * rework (migration 015); the value now lives directly on the site (sites.max_key_access_
     * duration_minutes), backfilled from each site's former region at migration time. */
    const val ADMIN_KEY_ACCESS_REQUEST_SITE_POLICY = "/v1/admin/key-access-requests/site-policy/{siteId}"
    /** Sites the caller may request as exception access (ACTIVE sites not in their standing assignments). */
    const val ADMIN_KEY_ACCESS_EXCEPTION_SITES = "/v1/admin/key-access-requests/exception-sites"
    /** Keys at an exception-eligible site (Only B Apply key picker). */
    const val ADMIN_KEY_ACCESS_EXCEPTION_SITE_KEYS =
        "/v1/admin/key-access-requests/exception-sites/{siteId}/keys"
    /** Unauthenticated — a terminal-side operator entering a passkey has no token yet, same
     * reasoning as [TERMINAL_PAIR_WITH_CODE]. See [TerminalPasskeyLoginRequest]. Backend route
     * only as of migration 009 — terminalApp's UI is not wired to this endpoint yet. */
    const val TERMINAL_PASSKEY_LOGIN = "/v1/terminal/passkey-login"
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
    const val REPORTS_ACTIVITY_LOGS = "/v1/reports/activity-logs"
    const val REPORTS_ACTIVITY_SUMMARY = "/v1/reports/activity-summary"
    const val REPORTS_EXPORTS = "/v1/reports/exports"
    const val RECYCLE_BIN = "/v1/admin/recycle-bin"
    /** Super Admin only. Permanent data wipe preview + execute (not Recycle Bin). */
    const val ADMIN_FLUSH_PREVIEW = "/v1/admin/flush/preview"
    const val ADMIN_FLUSH = "/v1/admin/flush"
    const val ADMIN_ROLE_CAPABILITIES = "/v1/admin/role-capabilities"
    const val ADMIN_ROLE_CAPABILITIES_ME = "/v1/admin/role-capabilities/me"
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
    val staffId: String? = null,
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

/**
 * One-time device pairing (Web Portal handoff — see CLAUDE.md "Web Portal — Pending UI Work
 * (Registration Workflow)" for the full flow this backs).
 *
 * Replaces the old "Set server address -> Key Cabinet ID -> sign in with a Super Admin's own
 * email/password" terminal setup path. A 6-digit numeric code is generated when a Super Admin
 * registers a Key Cabinet on the web portal (`POST /v1/admin/terminals`) or explicitly
 * regenerates one (`POST /v1/admin/terminals/{id}/pairing-code`); the terminal submits it here,
 * unauthenticated, exactly once.
 *
 * Server-side invariants (see backend/src/routes/pairing.js):
 * - Code expires 30 minutes after generation.
 * - Single-use: consumed on first successful pairing, a second submission of the same code
 *   always fails even if not yet expired.
 * - Only the SHA-256 hash of the code is ever stored; the plaintext value exists only in the
 *   one-time generation response (TerminalRegistrationResponse / RegeneratePairingCodeResponse).
 * - Rate-limited per source IP address (see `pairing_attempts` table) — a 6-digit code is only
 *   1,000,000 possible values, so brute-forcing this endpoint is a real risk, not theoretical.
 */
@Serializable
data class TerminalPairWithCodeRequest(
    val code: String,
)

/**
 * Tokens are TERMINAL_DEVICE-scoped, not a human user's tokens — see the "Route enumeration"
 * note on `requireSuperAdminOrAllowedTerminalDevice` in backend/src/middleware/auth.js for
 * exactly which admin routes a terminal-scoped token may call. Shape deliberately
 * mirrors [LoginResponse]'s token fields (accessToken/refreshToken/expiresAtEpochMillis) so
 * terminalApp's existing token-storage code needs no structural change — only [terminal]
 * replaces [LoginResponse.profile] as the thing being authenticated.
 */
@Serializable
data class TerminalPairingResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val terminal: TerminalDto,
)

/**
 * Super Admin action on an existing terminal record — lost device, factory reset, or
 * re-pairing to a different physical unit. Immediately revokes that terminal's current
 * TERMINAL_DEVICE refresh token(s) (if any), so an old/lost device cannot keep syncing after
 * a new code is issued. Does NOT affect a terminal still running the legacy manual-login
 * pairing path (that path's tokens are ordinary Super Admin user tokens, tracked separately —
 * see CLAUDE.md's terminal-pairing migration note).
 */
@Serializable
data class RegeneratePairingCodeResponse(
    val terminalId: String,
    /** Plaintext 6-digit code — present only in this one response. Never retrievable again. */
    val code: String,
    val expiresAtEpochMillis: Long,
)

@Serializable
data class SiteDto(
    val id: String,
    val name: String,
    val province: String? = null,
    val city: String? = null,
    /** Superior / parent unit id, when this unit sits under another site. */
    val parentSiteId: String? = null,
    val address: String? = null,
    /** Region assignment (migration 009) — was added to the `sites` table then but never
     * actually exposed via the API until now (a confirmed gap: nothing could assign a site to a
     * region at all). See [RegionDto]; null until a Super Admin assigns one. */
    val regionId: String? = null,
    val revision: Long,
)

@Serializable
data class TerminalDto(
    val id: String,
    val siteId: String,
    /** Owning [SiteDto]'s human-readable name, joined server-side for display (e.g. the
     * terminal-app top bar) — not authoritative, [siteId] is. Null on older servers. */
    val siteName: String? = null,
    val name: String,
    val boxAddress: Int,
    val serialNumber: String? = null,
    /** Server-validated 1–127 (docs/Key Cabinet Communication Protocol.md §7.1). */
    val configuredSlotCount: Int = 0,
    val cabinetSerialPort: String? = null,
    val cabinetBaudRate: Int? = null,
    val connectionState: TerminalConnectionState = TerminalConnectionState.UNKNOWN,
    /** Vendor-assigned physical device ID, distinct from [id] (backend UUID). See "Web Portal — Pending UI Work" in CLAUDE.md. */
    val vendorDeviceId: String? = null,
    val nodeRows: Int? = null,
    val nodesPerRow: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** True once this terminal has completed one-time-code pairing (see TerminalPairWithCodeRequest). */
    val paired: Boolean = false,
    val revision: Long,
)

@Serializable
data class UserDto(
    val id: String,
    val displayName: String,
    val email: String,
    val role: UserRole,
    val assignedSiteIds: Set<String> = emptySet(),
    /**
     * Region assignment (migration 009) — a Regional Admin may be assigned to one or more
     * [RegionDto]s, independently of and in addition to [assignedSiteIds]. Region assignment's
     * only job is routing a Technician/Vendor's [KeyAccessRequestDto] to the right Regional
     * Admin for approval — it does not replace or need to be kept consistent with
     * assignedSiteIds (a Regional Admin assigned to a Region need not also be individually
     * assigned to every Site inside it — a deliberate simplification, see the backend migration
     * doc). Empty/irrelevant for every role except REGIONAL_ADMIN.
     */
    val assignedRegionIds: Set<String> = emptySet(),
    val accountStatus: AccountStatus = AccountStatus.ACTIVE,
    /** External/employee identifier, distinct from [id]. */
    val staffId: String? = null,
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
    /** Region assignment (migration 009) — independent of [assignedSiteIds]; only meaningful for
     * REGIONAL_ADMIN. See [UserDto.assignedRegionIds] for the full "why separate" reasoning. */
    val assignedRegionIds: Set<String> = emptySet(),
    val password: String? = null,
    /** External/employee identifier; optional, not required for account creation. */
    val staffId: String? = null,
)

@Serializable
data class UpdateAdminUserRequest(
    val displayName: String,
    val email: String,
    val role: UserRole,
    val assignedSiteIds: Set<String>,
    val assignedRegionIds: Set<String> = emptySet(),
    val staffId: String? = null,
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
data class CompleteCredentialEnrollmentRequest(
    val credentialKind: CredentialKind,
    /** Opaque reference only (e.g. cardref_…) — never a raw NFC UID. */
    val enrollmentReference: String,
    val terminalId: String? = null,
    val expectedRevision: Long? = null,
    val note: String? = null,
)

@Serializable
data class RevokeCredentialEnrollmentRequest(
    val credentialKind: CredentialKind,
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
    val enrollmentReference: String? = null,
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
    /** Present on successful download; authoritative cabinet config for this terminal. */
    val snapshot: TerminalDownloadSnapshot? = null,
)

/**
 * Authoritative site-scoped config for one physical cabinet.
 * Never includes passwords or raw NFC/biometric material.
 */
@Serializable
data class TerminalDownloadSnapshot(
    val terminal: Terminal,
    val users: List<AdminUser>,
    val keys: List<ManagedKey>,
    val keySlots: List<KeySlot>,
    val accessGrants: List<AccessGrant>,
    /**
     * Portal-managed Take/Return timers and toggles. Null on older servers;
     * terminalApp keeps local defaults when absent.
     */
    val cabinetSettings: TerminalCabinetSettingsDto? = null,
)

/** Portal + sync DTO for terminal behavioral settings (Admin Menu timing/video/certification). */
@Serializable
data class TerminalCabinetSettingsDto(
    val terminalId: String? = null,
    val takeWarningTimeSeconds: Int = 15,
    val doorCloseWarningTimeSeconds: Int = 15,
    val keyReturnCertificationEnabled: Boolean = false,
    val returnKeyVideoEnabled: Boolean = false,
    val keyRetrievalVideoEnabled: Boolean = false,
    val revision: Long = 1,
)

@Serializable
data class UpdateTerminalCabinetSettingsRequest(
    val takeWarningTimeSeconds: Int,
    val doorCloseWarningTimeSeconds: Int,
    val keyReturnCertificationEnabled: Boolean,
    val returnKeyVideoEnabled: Boolean,
    val keyRetrievalVideoEnabled: Boolean,
    val expectedRevision: Long,
)

/** API HANDOVER — Sites & Terminals. All PATCH requests use revision checks. */
@Serializable
data class SiteUpsertRequest(
    val name: String,
    val province: String? = null,
    val city: String? = null,
    /** Superior unit id; omit or null for a top-level unit. */
    val parentSiteId: String? = null,
    val address: String? = null,
    /** Region assignment (migration 009) — see [SiteDto.regionId]. */
    val regionId: String? = null,
    /** Required for PATCH and must match the current backend revision. */
    val expectedRevision: Long? = null,
)

@Serializable
data class TerminalUpsertRequest(
    val siteId: String,
    val name: String,
    val boxAddress: Int,
    val serialNumber: String? = null,
    /** Server rejects anything outside 1–127 — see docs/Key Cabinet Communication Protocol.md §7.1. */
    val configuredSlotCount: Int,
    val cabinetSerialPort: String? = null,
    val cabinetBaudRate: Int? = null,
    val vendorDeviceId: String? = null,
    val nodeRows: Int? = null,
    val nodesPerRow: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val expectedRevision: Long? = null,
)

/**
 * Response shape for `POST /v1/admin/terminals` (create/register) ONLY — a breaking change
 * from the old bare-[TerminalDto] response, since registration now also mints a one-time
 * pairing code that must be shown to the Super Admin immediately (never retrievable again
 * after this response). `PATCH`/`GET`/list terminal endpoints still return plain [TerminalDto]
 * — this wrapper exists only where a fresh code is actually being minted.
 */
@Serializable
data class TerminalRegistrationResponse(
    val terminal: TerminalDto,
    val pairingCode: String,
    val pairingCodeExpiresAtEpochMillis: Long,
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
 * Request body for [ApiPaths.ADMIN_KEY_FOB_ENROLLMENT_COMPLETE]. [enrollmentReference] is an
 * opaque reference only (e.g. `cardref_<uuid>`, the same convention
 * `EncryptedUidEnrollmentStore` already generates) — never a raw NFC UID, enforced server-side
 * too (boundary #2).
 */
@Serializable
data class FobEnrollmentCompleteRequest(
    val enrollmentReference: String,
    val terminalId: String,
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
    val changesJson: List<String> = emptyList(),
    /** Same cabinet snapshot as download, so first sync can hydrate without a second call. */
    val snapshot: TerminalDownloadSnapshot? = null,
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
    ACTIVITY_LOGS,
}

/** Cross-platform Activity Report filter buckets (maps to audit event types server-side). */
@Serializable
enum class ReportCategory {
    KEY_TAKE,
    KEY_RETURN,
    CABINET_REGISTRATION,
    PERSONNEL_REGISTRATION,
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
    /** Activity Report category filters; empty/null means all four categories. */
    val categories: List<ReportCategory>? = null,
)

@Serializable
data class ActivityLogRow(
    val id: String,
    val occurredAtEpochMillis: Long,
    val eventType: String,
    val category: ReportCategory,
    val terminalId: String? = null,
    val siteId: String? = null,
    val actorUserId: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val detail: String? = null,
    val siteName: String? = null,
    val terminalName: String? = null,
    val actorName: String? = null,
)

@Serializable
data class ActivityLogListResponse(val items: List<ActivityLogRow> = emptyList())

@Serializable
data class ActivitySummaryResponse(
    val total: Int = 0,
    val byCategory: Map<ReportCategory, Int> = emptyMap(),
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

/**
 * Phase 1 (Revised) — Regional Admin, checkout records, office hours, vendor passkey. Backend
 * schema/validation only in this pass; no terminalApp hardware wiring or web UI consumes these
 * yet (see CLAUDE.md's handoff notes for what each phase still needs).
 */

/** [status] is only ever OPEN or RETURNED on the stored row — [effectiveStatus] additionally
 * reports OVERDUE, derived at read time (status == OPEN && dueAtEpochMillis < now), never
 * stored or cron-flipped. Always read [effectiveStatus] for UI/business-rule purposes; [status]
 * is the raw persisted value. */
@Serializable
enum class KeyCheckoutStatus {
    OPEN,
    OVERDUE,
    RETURNED,
}

@Serializable
enum class KeyCheckoutExtensionStatus {
    PENDING,
    APPROVED,
    DENIED,
}

@Serializable
data class KeyCheckoutDto(
    val id: String,
    val keyId: String,
    val userId: String,
    val terminalId: String,
    val takenAtEpochMillis: Long,
    val dueAtEpochMillis: Long,
    val status: KeyCheckoutStatus,
    val effectiveStatus: KeyCheckoutStatus,
    val isEmergency: Boolean = false,
    val emergencyWindowEndsAtEpochMillis: Long? = null,
    val extensionRequestedAtEpochMillis: Long? = null,
    val extensionStatus: KeyCheckoutExtensionStatus? = null,
    val extensionApprovedByUserId: String? = null,
    val extensionNewDueAtEpochMillis: Long? = null,
    val returnedAtEpochMillis: Long? = null,
    val revision: Long,
)

/**
 * [CreateKeyCheckoutRequest.dueAtEpochMillis]/[UpdateKeyCheckoutRequest.dueAtEpochMillis] is
 * reused as the effective deadline regardless of source (auto-computed vs. manually entered vs.
 * emergency) — a deliberate decision, no parallel column. [DueDateSource] is not persisted on the
 * row; it only labels which path set the deadline this time, logged via the corresponding
 * audit_events entry (`KEY_CHECKOUT_CREATED` on create, `KEY_CHECKOUT_UPDATED`/
 * `KEY_CHECKOUT_RETURNED` on update). [EMERGENCY] is a Phase 5 addition — the close-to-deadline
 * decision only ever happens once, at take time (`CreateKeyCheckoutRequest`); the backend's
 * `PATCH /key-checkouts/:id` route still only accepts AUTO/MANUAL, since return/close-out never
 * re-decides emergency status.
 */
@Serializable
enum class DueDateSource {
    AUTO,
    MANUAL,
    EMERGENCY,
    /** Migration 009 follow-up: the due time was already fixed at key-access-request approval
     * time (the approved [KeyAccessRequestDto.requestedDurationMinutes]/`passkeyExpiresAtEpochMillis`),
     * not decided at take time by either an algorithm (AUTO) or an operator (MANUAL/EMERGENCY).
     * A genuinely distinct provenance, not reused from an existing value, since AUTO specifically
     * means "computed from office hours" and this due time has nothing to do with office hours
     * at all. Like EMERGENCY, this is CREATE-only — `PATCH /key-checkouts/:id` (return/close-out)
     * still only accepts AUTO/MANUAL, since a return never re-decides how the due date was set. */
    PASSKEY_REQUEST,
}

@Serializable
data class CreateKeyCheckoutRequest(
    val keyId: String,
    val userId: String,
    val terminalId: String,
    val takenAtEpochMillis: Long,
    val dueAtEpochMillis: Long,
    val isEmergency: Boolean = false,
    val emergencyWindowEndsAtEpochMillis: Long? = null,
    val dueDateSource: DueDateSource = DueDateSource.AUTO,
)

@Serializable
data class UpdateKeyCheckoutRequest(
    val dueAtEpochMillis: Long,
    val dueDateSource: DueDateSource = DueDateSource.AUTO,
    val status: KeyCheckoutStatus,
    val returnedAtEpochMillis: Long? = null,
    val isEmergency: Boolean = false,
    val emergencyWindowEndsAtEpochMillis: Long? = null,
    val extensionRequestedAtEpochMillis: Long? = null,
    val extensionStatus: KeyCheckoutExtensionStatus? = null,
    val extensionApprovedByUserId: String? = null,
    val extensionNewDueAtEpochMillis: Long? = null,
    val expectedRevision: Long,
)

@Serializable
data class KeyCheckoutListResponse(val items: List<KeyCheckoutDto> = emptyList())

/** Per-site office hours, including timezone — same per-site granularity as Site's own
 * province/city fields. Defaults to Asia/Kuala_Lumpur (this system is Malaysia-only, see
 * web/src/geo/malaysiaLocations.ts), not UTC — flag any site that turns out to need a different
 * real value. */
@Serializable
data class SiteOfficeHoursDto(
    val siteId: String,
    /** "HH:MM:SS", 24-hour. */
    val openTime: String = "08:00:00",
    /** "HH:MM:SS", 24-hour. */
    val closeTime: String = "17:00:00",
    val timezone: String = "Asia/Kuala_Lumpur",
    val updatedByUserId: String? = null,
    val updatedAtEpochMillis: Long,
    val revision: Long = 1,
)

@Serializable
data class UpdateSiteOfficeHoursRequest(
    val openTime: String,
    val closeTime: String,
    val timezone: String,
    val expectedRevision: Long,
)

/**
 * Deliberately minimal placeholder — full request/approval UX (notification delivery, mobile
 * app UI, terminal-side passkey validation) is designed later in the mobileApp phase. No
 * `expectedRevision`: approve/reject are single state-transition actions guarded by checking
 * `status == PENDING` at write time, not a general field-level PATCH.
 *
 * Still live in production — backs terminalApp's deployed Phase 7 `TerminalVendorPasskeyScreen`
 * (item 16). Kept exactly as-is; [KeyAccessRequestDto] below is an additive, more general
 * mechanism for mobileApp going forward (Region-routed, multi-key, Technician+Vendor), not a
 * rename or replacement of this one — both coexist.
 */
@Serializable
enum class VendorPasskeyRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

/** [passkeyExpiresAtEpochMillis] is present once approved; the plaintext code itself is never
 * included here — see [ApproveVendorPasskeyRequestResponse], which shows it exactly once. */
@Serializable
data class VendorPasskeyRequestDto(
    val id: String,
    val vendorUserId: String,
    val siteId: String,
    val requestedAtEpochMillis: Long,
    val status: VendorPasskeyRequestStatus,
    val approvedByUserId: String? = null,
    val approvedAtEpochMillis: Long? = null,
    val passkeyExpiresAtEpochMillis: Long? = null,
)

@Serializable
data class CreateVendorPasskeyRequestRequest(
    val vendorUserId: String,
    val siteId: String,
)

@Serializable
data class VendorPasskeyRequestListResponse(val items: List<VendorPasskeyRequestDto> = emptyList())

/** [passkeyCode] is a plaintext 4-digit code, shown exactly once — same "shown once" treatment
 * as terminal pairing codes (RegeneratePairingCodeResponse). Nothing re-reads it afterward. */
@Serializable
data class ApproveVendorPasskeyRequestResponse(
    val id: String,
    val status: VendorPasskeyRequestStatus = VendorPasskeyRequestStatus.APPROVED,
    val passkeyCode: String,
    val passkeyExpiresAtEpochMillis: Long,
)

/**
 * A geographic grouping ABOVE Site (migration 009) — additive, not a replacement for
 * `assignedSiteIds`/`user_site_assignments`. Region's one job: route a Technician/Vendor's
 * [KeyAccessRequestDto] to the right Regional Admin, since the (not-yet-built, per the
 * mobileApp foundation pass's own scope) request form only lets the requester pick a key/
 * cabinet, not a person to approve it. Super-Admin-managed only — a Regional Admin is assigned
 * INTO a region (via [UserDto.assignedRegionIds]), never creates/edits regions themselves.
 */
@Serializable
data class RegionDto(
    val id: String,
    val name: String,
    /** Optional display ordering for a future portal region list; not load-bearing elsewhere. */
    val displayOrder: Int = 0,
    /** Ceiling (in minutes) that [KeyAccessRequestDto.requestedDurationMinutes] is clamped to at
     * approval time — the "fixed/default return timing policy" a Regional Admin sets for their
     * whole region. Default 1440 (24h) matches the prior fixed vendor-passkey TTL. */
    val maxKeyAccessDurationMinutes: Int = 1440,
    val revision: Long,
)

@Serializable
data class CreateRegionRequest(
    val name: String,
    val displayOrder: Int = 0,
    val maxKeyAccessDurationMinutes: Int = 1440,
)

@Serializable
data class UpdateRegionRequest(
    val name: String,
    val displayOrder: Int = 0,
    val maxKeyAccessDurationMinutes: Int = 1440,
    val expectedRevision: Long,
)

@Serializable
data class RegionListResponse(val items: List<RegionDto> = emptyList())

/**
 * Generalizes the old VendorPasskeyRequestDto (Phase 1) beyond Vendor-only, now that Technician
 * also gets passkey access (migration 009). Approval routes through the request's [siteId]'s
 * [RegionDto] assignment, not direct Site assignment — see [UserDto.assignedRegionIds]. No
 * `expectedRevision`: approve/reject are single state-transition actions guarded by checking
 * `status == PENDING` at write time, same as the table this generalizes.
 */
@Serializable
enum class KeyAccessRequestStatus {
    PENDING,
    /** Vendor Stage 1 — waiting for Person In Charge (Technician at site). */
    PENDING_PIC,
    /** Vendor Stage 2 — PIC approved; waiting for Regional Admin. */
    PENDING_RA,
    APPROVED,
    REJECTED,
    /** Admin cancelled an approved PIN — passkey cleared; terminal passkey-login must fail. */
    REVOKED,
    /** Pickup/return window ended without use (or past return) — must resubmit a new request.
     * An APPROVED request whose PIN window lapses without ever being used does NOT reach this
     * state automatically anymore — the backend's `keyAccessAutoExtend.js` tick job auto-extends
     * it by 1 hour, repeating, instead (tracked server-side only, via
     * `key_access_requests.first_used_at_epoch_ms` — not surfaced on this DTO, since nothing in
     * mobileApp needs to display "used" state for this feature to work). EXPIRED still applies to
     * PENDING/PENDING_PIC/PENDING_RA rows whose return window passes before ever being approved,
     * and to an APPROVED row after it has been used at least once (auto-extension stops there, so
     * a subsequent lapse is terminal). */
    EXPIRED,
    /** Requester (Vendor/Technician) self-cancelled — distinct from [REVOKED] (admin-initiated)
     * purely for audit-trail clarity about who ended the request. Same terminal, no-revive
     * treatment: reaching the cabinet again requires a brand-new request, not a resubmission. */
    CANCELLED,
}

/** [passkeyExpiresAtEpochMillis] is present once approved; the plaintext code itself is never
 * included here — see [ApproveKeyAccessRequestResponse], which shows it exactly once. */
@Serializable
data class KeyAccessRequestDto(
    val id: String,
    val requesterUserId: String,
    /** TECHNICIAN or VENDOR — reuses [UserRole]'s existing values, no new enum. */
    val requesterRole: UserRole,
    val siteId: String,
    /** Display name of [siteId] — filled by list/get/create for mobile PIN/cabinet copy. */
    val siteName: String? = null,
    /** Requester display name — for admin/web approval queues (not secrets). */
    val requesterDisplayName: String? = null,
    /** Active key-cabinet names at [siteId] — which terminal(s) to use the passkey on. */
    val cabinetNames: List<String> = emptyList(),
    /** Multi-key support: mirrors [AccessGrantDto.keyIds] rather than a single key id — a
     * request can cover one key or several (mirroring Key Menu's multi-key take), decided as
     * the schema shape that's easiest to extend either way. */
    val keyIds: Set<String> = emptySet(),
    val requestedAtEpochMillis: Long,
    /** Derived as (return − pickup) in minutes for Only B calendar requests. Legacy rows may
     * only have this field (pre-calendar). Region ceiling is NOT applied for Only B. */
    val requestedDurationMinutes: Int,
    /** Free-text reason — required for new Only B creates; may be null on legacy rows. */
    val reason: String? = null,
    val pickupAtEpochMillis: Long? = null,
    val returnAtEpochMillis: Long? = null,
    val status: KeyAccessRequestStatus,
    /** Vendor Stage-1 PIC (Technician at site); null for Technician-only requests. */
    val picUserId: String? = null,
    val picApprovedAtEpochMillis: Long? = null,
    /** Metadata only (Work Permit/NIOSH/IC uploads at create time) — never includes bytes, and
     * always empty for Technician requests (documents are Vendor-only, see
     * [CreateKeyAccessRequestRequest.documents]). Fetch a given document's actual bytes via
     * [ApiPaths.ADMIN_KEY_ACCESS_REQUEST_DOCUMENT_DOWNLOAD]. */
    val documents: List<KeyAccessRequestDocumentMeta> = emptyList(),
    val approvedByUserId: String? = null,
    val approvedAtEpochMillis: Long? = null,
    /** Non-null only when the caller viewing this DTO IS the request's own [requesterUserId] —
     * see the viewer-scoping note on `mapRequest` in backend/src/routes/keyAccessRequests.js.
     * Everyone else (a Regional Admin reviewing it, a different requester) always gets `null`
     * here; the [ApproveKeyAccessRequestResponse] the approver receives is a separate, one-time
     * value and does not affect this field. */
    val generatedPasskey: String? = null,
    val passkeyExpiresAtEpochMillis: Long? = null,
)

@Serializable
data class CreateKeyAccessRequestRequest(
    /** Only honored for a SUPER_ADMIN/REGIONAL_ADMIN caller creating a request on someone
     * else's behalf — a TECHNICIAN/VENDOR caller always requests for themselves server-side,
     * regardless of what (if anything) is sent here. See backend/src/routes/keyAccessRequests.js. */
    val requesterUserId: String? = null,
    val requesterRole: UserRole? = null,
    val siteId: String,
    val keyIds: Set<String>,
    /** Required for Only B. */
    val reason: String,
    val pickupAtEpochMillis: Long,
    val returnAtEpochMillis: Long,
    /** Required for Vendor — Technician PIC at the exception site. */
    val picUserId: String? = null,
    val documents: List<KeyAccessRequestDocumentUpload> = emptyList(),
)

@Serializable
data class KeyAccessRequestDocumentUpload(
    val docKind: String,
    val fileName: String,
    val contentType: String = "application/octet-stream",
    val contentBase64: String,
)

/** Read-only metadata for one document attached to a [KeyAccessRequestDto] — never carries the
 * bytes themselves (see [ApiPaths.ADMIN_KEY_ACCESS_REQUEST_DOCUMENT_DOWNLOAD] for that). Embedded
 * directly on [KeyAccessRequestDto.documents] so a PIC/Regional Admin's approval queue can show
 * "N documents attached" without a separate list round-trip before deciding whether to view one. */
@Serializable
data class KeyAccessRequestDocumentMeta(
    val id: String,
    val docKind: String,
    val fileName: String,
    val contentType: String = "application/octet-stream",
    val sizeBytes: Long,
    val createdAtEpochMillis: Long,
)

@Serializable
data class SitePicCandidateDto(
    val id: String,
    val displayName: String,
    val email: String = "",
)

@Serializable
data class SitePicListResponse(val items: List<SitePicCandidateDto> = emptyList())

@Serializable
data class RegisterMobilePushTokenRequest(
    val fcmToken: String,
    val platform: String = "ANDROID",
)

@Serializable
data class KeyAccessRequestListResponse(val items: List<KeyAccessRequestDto> = emptyList())

/** [generatedPasskey] is a plaintext 4-digit code, shown exactly once — same "shown once"
 * treatment as terminal pairing codes (RegeneratePairingCodeResponse). Nothing re-reads it
 * afterward. Server-generated only — a client can never submit its own passkey value. */
@Serializable
data class ApproveKeyAccessRequestResponse(
    val id: String,
    val status: KeyAccessRequestStatus = KeyAccessRequestStatus.APPROVED,
    val generatedPasskey: String,
    val passkeyExpiresAtEpochMillis: Long,
)

/**
 * Terminal-side passkey login (migration 009) — submits the 4-digit code from
 * [ApproveKeyAccessRequestResponse.generatedPasskey] at the terminal's existing (currently
 * non-functional) Passkey login tile. Unauthenticated, same reasoning as
 * [TerminalPairWithCodeRequest]. [terminalId] lets the backend confirm the passkey's approved
 * site matches the terminal being logged into — a passkey approved for one cabinet's site must
 * not be usable at an unrelated terminal elsewhere.
 *
 * Backend route only as of this migration — terminalApp's `TerminalPasskeyLoginScreen` is not
 * wired to this endpoint yet (still the disabled UI shell from Phase 3); that wiring is
 * deliberately deferred, separate follow-up work.
 */
@Serializable
data class TerminalPasskeyLoginRequest(
    val passkey: String,
    val terminalId: String,
)

/**
 * [accessToken] is a KEY_ACCESS_SESSION-scoped JWT (see `signKeyAccessSessionToken` in
 * backend/src/middleware/auth.js) carrying exactly [keyIds]/[siteId] as claims and expiring at
 * [expiresAtEpochMillis] — no refresh token exists for this session type, it is not meant to be
 * renewed. [requesterUserId] plus [requesterDisplayName]/[requesterEmail]/[requesterRole] are
 * plain fields (not only inside the opaque JWT) so terminalApp can build a normal
 * `TerminalSession` without decoding a JWT and without requiring the requester to already be in
 * this cabinet's local personnel snapshot — Only B exception techs are deliberately *not*
 * site-assigned, so bootstrap never downloads them.
 */
@Serializable
data class TerminalPasskeyLoginResponse(
    val accessToken: String,
    val keyAccessRequestId: String,
    val requesterUserId: String,
    val requesterDisplayName: String = "",
    val requesterEmail: String = "",
    val requesterRole: String = "TECHNICIAN",
    val siteId: String,
    val keyIds: Set<String> = emptySet(),
    val expiresAtEpochMillis: Long,
)

/**
 * Response for [ApiPaths.ADMIN_KEY_ACCESS_REQUEST_SITE_POLICY] — the one derived value a
 * requester's mobile form needs before submitting a [CreateKeyAccessRequestRequest]:
 * [maxKeyAccessDurationMinutes] bounds the duration picker client-side for UX only — never trust
 * this value as security (nothing server-side currently clamps to it at approve time either;
 * flagged as a pre-existing doc/code mismatch, not something this DTO enforces).
 * As of the "regional confusion" rework (migration 015), this is read directly off the site
 * (`sites.max_key_access_duration_minutes`), no longer derived through the site's Region —
 * [regionId] is still returned (sites.region_id survives as a cosmetic field) but no longer
 * determines [maxKeyAccessDurationMinutes]. `null` if the site has no policy value set (no
 * ceiling to enforce) — same meaning as before, just no longer tied to having a Region assigned.
 */
@Serializable
data class SiteKeyAccessPolicyDto(
    val siteId: String,
    val regionId: String? = null,
    val maxKeyAccessDurationMinutes: Int? = null,
)