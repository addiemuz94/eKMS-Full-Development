package com.ekms.shared.domain

import kotlinx.serialization.Serializable

/**
 * Account status is separate from lifecycle. A disabled user is retained and
 * may be enabled again; a recycle-bin user is a deleted record awaiting
 * restoration or permanent purge.
 */
@Serializable
enum class AccountStatus {
    ACTIVE,
    DISABLED,
}

@Serializable
enum class CredentialEnrollmentStatus {
    NOT_ASSIGNED,
    PENDING_TERMINAL_ENROLLMENT,
    ACTIVE,
    REVOKED,
}

/** Safe credential metadata for UI lists. It never contains an NFC UID, passkey, or biometric template. */
@Serializable
data class UserCredentialStatus(
    val userId: String,
    val kind: CredentialKind,
    val status: CredentialEnrollmentStatus,
    val detail: String,
)

@Serializable
data class ManagedSiteOption(
    val id: String,
    val label: String,
)

/** Draft used by Website and Terminal. Credential capture itself remains Terminal-only. */
@Serializable
data class UserDraft(
    val displayName: String,
    val email: String,
    val role: UserRole,
    val assignedSiteIds: Set<String>,
)

/**
 * Shared, side-effect-free business rules. The backend must enforce the same
 * rules authoritatively once the API is connected.
 */
object UserManagementPolicy {
    fun validateDraft(
        draft: UserDraft,
        knownSiteIds: Set<String>,
    ): String? = when {
        draft.displayName.trim().length < 2 -> "Enter a name with at least 2 characters."
        !draft.email.trim().contains("@") -> "Enter a valid email address."
        !knownSiteIds.containsAll(draft.assignedSiteIds) -> "Remove an unknown site assignment."
        draft.role != UserRole.SUPER_ADMIN && draft.assignedSiteIds.isEmpty() ->
            "Technicians and Vendors must be assigned to at least one site."
        else -> null
    }

    fun createUser(
        id: String,
        draft: UserDraft,
        nowEpochMillis: Long,
    ): AdminUser = AdminUser(
        id = id,
        displayName = draft.displayName.trim(),
        email = draft.email.trim().lowercase(),
        role = draft.role,
        assignedSiteIds = draft.assignedSiteIds,
        accountStatus = AccountStatus.ACTIVE,
        lifecycle = LifecycleMetadata(
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
        ),
    )

    fun updateUser(
        existing: AdminUser,
        draft: UserDraft,
        nowEpochMillis: Long,
    ): AdminUser = existing.copy(
        displayName = draft.displayName.trim(),
        email = draft.email.trim().lowercase(),
        role = draft.role,
        assignedSiteIds = draft.assignedSiteIds,
        lifecycle = existing.lifecycle.copy(updatedAtEpochMillis = nowEpochMillis),
    )

    fun setAccountStatus(
        existing: AdminUser,
        status: AccountStatus,
        nowEpochMillis: Long,
    ): AdminUser = existing.copy(
        accountStatus = status,
        lifecycle = existing.lifecycle.copy(updatedAtEpochMillis = nowEpochMillis),
    )

    fun moveToRecycleBin(
        existing: AdminUser,
        actorUserId: String,
        nowEpochMillis: Long,
    ): AdminUser = existing.copy(
        lifecycle = existing.lifecycle.copy(
            state = RecordLifecycle.RECYCLE_BIN,
            updatedAtEpochMillis = nowEpochMillis,
            deletedAtEpochMillis = nowEpochMillis,
            deletedByUserId = actorUserId,
        ),
    )

    fun restoreFromRecycleBin(
        existing: AdminUser,
        nowEpochMillis: Long,
    ): AdminUser = existing.copy(
        lifecycle = existing.lifecycle.copy(
            state = RecordLifecycle.ACTIVE,
            updatedAtEpochMillis = nowEpochMillis,
            deletedAtEpochMillis = null,
            deletedByUserId = null,
        ),
    )

    fun permanentlyPurge(
        existing: AdminUser,
        nowEpochMillis: Long,
    ): AdminUser = existing.copy(
        lifecycle = existing.lifecycle.copy(
            state = RecordLifecycle.PURGED,
            updatedAtEpochMillis = nowEpochMillis,
        ),
    )
}

/** Local preview data only. Replace this repository source with the documented backend APIs. */
object SuperAdminDemoData {
    private const val CREATED_AT = 1_783_000_000_000L

    val sites = listOf(
        ManagedSiteOption(id = "site_hq", label = "Head Office · Demo"),
        ManagedSiteOption(id = "site_port", label = "Port Operations · Demo"),
        ManagedSiteOption(id = "site_service", label = "Service Centre · Demo"),
    )

    fun users(): List<AdminUser> = listOf(
        AdminUser(
            id = "usr_super_admin_demo",
            displayName = "Super Admin Demo",
            email = "superadmin@ekms.demo",
            role = UserRole.SUPER_ADMIN,
            assignedSiteIds = sites.mapTo(linkedSetOf()) { it.id },
            lifecycle = activeLifecycle(),
        ),
        AdminUser(
            id = "usr_technician_demo",
            displayName = "Technician Demo",
            email = "technician@ekms.demo",
            role = UserRole.TECHNICIAN,
            assignedSiteIds = setOf("site_port"),
            lifecycle = activeLifecycle(),
        ),
        AdminUser(
            id = "usr_vendor_demo",
            displayName = "Vendor Demo",
            email = "vendor@ekms.demo",
            role = UserRole.VENDOR,
            assignedSiteIds = setOf("site_service"),
            accountStatus = AccountStatus.DISABLED,
            lifecycle = activeLifecycle(),
        ),
    )

    fun credentialsFor(user: AdminUser): List<UserCredentialStatus> = when (user.role) {
        UserRole.SUPER_ADMIN -> listOf(
            UserCredentialStatus(user.id, CredentialKind.NFC_CARD, CredentialEnrollmentStatus.ACTIVE, "Card assigned"),
            UserCredentialStatus(user.id, CredentialKind.FINGERPRINT, CredentialEnrollmentStatus.PENDING_TERMINAL_ENROLLMENT, "Enroll on Terminal"),
            UserCredentialStatus(user.id, CredentialKind.STATIC_UID_DIGITAL_KEY_PROTOTYPE, CredentialEnrollmentStatus.ACTIVE, "Prototype tag assigned"),
        )
        UserRole.TECHNICIAN -> listOf(
            UserCredentialStatus(user.id, CredentialKind.NFC_CARD, CredentialEnrollmentStatus.ACTIVE, "Card assigned"),
            UserCredentialStatus(user.id, CredentialKind.FINGERPRINT, CredentialEnrollmentStatus.NOT_ASSIGNED, "Not enrolled"),
            UserCredentialStatus(user.id, CredentialKind.STATIC_UID_DIGITAL_KEY_PROTOTYPE, CredentialEnrollmentStatus.PENDING_TERMINAL_ENROLLMENT, "Issue from Mobile later"),
        )
        UserRole.VENDOR -> listOf(
            UserCredentialStatus(user.id, CredentialKind.VENDOR_PASSKEY, CredentialEnrollmentStatus.NOT_ASSIGNED, "Created only after approval"),
            UserCredentialStatus(user.id, CredentialKind.FACE_RECOGNITION, CredentialEnrollmentStatus.NOT_ASSIGNED, "Coming soon"),
        )
    }

    private fun activeLifecycle() = LifecycleMetadata(
        createdAtEpochMillis = CREATED_AT,
        updatedAtEpochMillis = CREATED_AT,
    )
}