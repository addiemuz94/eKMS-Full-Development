package com.ekms.shared.domain

import kotlinx.serialization.Serializable

/** Roles are deliberately small at the start. Add roles only when their rules are defined. */
@Serializable
enum class UserRole {
    SUPER_ADMIN,
    TECHNICIAN,
    VENDOR,
}

@Serializable
enum class RecordType {
    USER,
    SITE,
    TERMINAL,
    KEY,
    KEY_SLOT,
    ACCESS_GRANT,
    CREDENTIAL,
}

@Serializable
enum class RecordLifecycle {
    ACTIVE,
    RECYCLE_BIN,
    PURGED,
}

@Serializable
data class LifecycleMetadata(
    val state: RecordLifecycle = RecordLifecycle.ACTIVE,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long? = null,
    val deletedByUserId: String? = null,
)

@Serializable
data class AdminUser(
    val id: String,
    val displayName: String,
    val email: String,
    val role: UserRole,
    val assignedSiteIds: Set<String> = emptySet(),
    /** Disabled users remain visible to Super Admins but cannot authenticate. */
    val accountStatus: AccountStatus = AccountStatus.ACTIVE,
    val lifecycle: LifecycleMetadata,
)

@Serializable
data class Site(
    val id: String,
    val name: String,
    val province: String? = null,
    val city: String? = null,
    val parentSiteId: String? = null,
    val address: String? = null,
    val lifecycle: LifecycleMetadata,
)

@Serializable
data class Terminal(
    val id: String,
    val siteId: String,
    val name: String,
    val boxAddress: Int,
    val serialNumber: String? = null,
    val lifecycle: LifecycleMetadata,
    /** Maximum configured key-node count for this cabinet. Valid node addresses are 1–127. */
    val configuredSlotCount: Int = 0,
    /** Android Terminal-only serial details; Web and Mobile may display but never open this port. */
    val cabinetSerialPort: String? = null,
    val cabinetBaudRate: Int? = null,
    val connectionState: TerminalConnectionState = TerminalConnectionState.UNKNOWN,
)

/** Configuration/status information only; never a command to operate cabinet hardware. */
@Serializable
enum class TerminalConnectionState {
    UNKNOWN,
    ONLINE,
    OFFLINE,
    SETUP_REQUIRED,
}

@Serializable
data class ManagedKey(
    val id: String,
    val siteId: String,
    val displayName: String,
    /**
     * Opaque value issued by the protected Android Terminal enrolment flow.
     * It is intentionally not a card UID and cannot be reversed to one by
     * Website, Mobile, or ordinary API responses.
     */
    val fobEnrollmentReference: String? = null,
    val lifecycle: LifecycleMetadata,
)

@Serializable
data class KeySlot(
    val id: String,
    val terminalId: String,
    /** Actual cabinet protocol node address. Never apply a hidden UI -1 conversion. */
    val nodeAddress: Int,
    val managedKeyId: String? = null,
    val lifecycle: LifecycleMetadata,
)

@Serializable
data class AccessGrant(
    val id: String,
    val userId: String,
    val siteId: String,
    val keyIds: Set<String>,
    val validFromEpochMillis: Long? = null,
    val validUntilEpochMillis: Long? = null,
    val lifecycle: LifecycleMetadata,
)

@Serializable
enum class CredentialKind {
    NFC_CARD,
    STATIC_UID_DIGITAL_KEY_PROTOTYPE,
    FINGERPRINT,
    FACE_RECOGNITION,
    VENDOR_PASSKEY,
}

@Serializable
data class CredentialBinding(
    val id: String,
    val userId: String,
    val kind: CredentialKind,
    /** NFC UIDs and passkey verifiers must be encrypted at rest in production. */
    val reference: String,
    val active: Boolean,
    val lifecycle: LifecycleMetadata,
)

@Serializable
data class AuditEvent(
    val id: String,
    val eventType: AuditEventType,
    val actorUserId: String?,
    val terminalId: String?,
    val siteId: String?,
    val entityType: RecordType? = null,
    val entityId: String? = null,
    val occurredAtEpochMillis: Long,
    val detail: String? = null,
)

@Serializable
enum class AuditEventType {
    LOGIN_SUCCEEDED,
    LOGIN_DENIED,
    KEY_TAKEN,
    KEY_RETURNED,
    USER_ACCOUNT_STATUS_CHANGED,
    USER_CREDENTIAL_ENROLLMENT_REQUESTED,
    RECORD_MOVED_TO_BIN,
    RECORD_RESTORED,
    RECORD_PURGED,
    CONFLICT_CREATED,
    CONFLICT_RESOLVED,
    SITE_CREATED,
    SITE_UPDATED,
    TERMINAL_CREATED,
    TERMINAL_UPDATED,
    TERMINAL_HARDWARE_CONFIGURATION_CHANGED,
    KEY_CREATED,
    KEY_UPDATED,
    KEY_FOB_ENROLLED,
    KEY_FOB_REPLACED,
    KEY_FOB_REVOKED,
    KEY_FOB_ENROLLMENT_DENIED,
    KEY_SLOT_CREATED,
    KEY_SLOT_UPDATED,
    ACCESS_GRANT_CREATED,
    ACCESS_GRANT_UPDATED,
}