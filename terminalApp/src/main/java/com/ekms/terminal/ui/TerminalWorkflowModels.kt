package com.ekms.terminal.ui

/**
 * Navigation for the supplier-aligned Terminal flow. This is deliberately
 * Android-Terminal-only UI state; backend synchronization replaces the local
 * demo state later.
 */
internal enum class TerminalRoute {
    STANDBY,
    LOGIN,
    KEY_PICKUP,
    PICKUP_VERIFIER,
    PICKUP_STATUS,
    RETURN_SCAN,
    RETURN_VERIFIER,
    RETURN_STATUS,
    MANAGEMENT_MENU,
    SITE_TERMINALS,
    KEY_REGISTRATION,
    KEY_DETAILS,
    FOB_ENROLLMENT,
    USERS,
    PERMISSIONS,
    RECORDS,
    APPOINTMENTS,
    EVENTS,
    SETTINGS,
    REAL_TIME_STATUS,
}

internal enum class WorkflowUserRole(val label: String) {
    REGULAR("Regular user"),
    ADMINISTRATOR("Administrator"),
}

internal data class WorkflowSession(
    val userId: String,
    val displayName: String,
    val role: WorkflowUserRole,
) {
    val isAdministrator: Boolean
        get() = role == WorkflowUserRole.ADMINISTRATOR
}

internal data class WorkflowUser(
    val id: String,
    val displayName: String,
    val role: WorkflowUserRole,
    val credentialSummary: String,
    val multiAuthenticationGroup: String? = null,
    val enabled: Boolean = true,
)

internal enum class WorkflowKeyStatus(val label: String) {
    PRESENT("Available"),
    OUT("Absent"),
    UNREGISTERED("Unregistered"),
    PICKUP_REQUESTED("Pickup requested"),
    RETURN_REQUESTED("Return requested"),
}

internal enum class KeyNodeType(val label: String) {
    MAIN_DOOR_WITH_KEY("Type A - main door with key"),
    SMALL_DOOR_WITH_KEY("Type B - small door with key"),
}

internal data class WorkflowKey(
    val id: String,
    val displayName: String,
    val nodeAddress: Int,
    val status: WorkflowKeyStatus,
    val permittedUserIds: Set<String>,
    val requiresDualAuthentication: Boolean = false,
    val enabled: Boolean = true,
    val fobEnrolled: Boolean = false,
    val overdueMinutes: Int? = null,
    val nodeType: KeyNodeType = KeyNodeType.MAIN_DOOR_WITH_KEY,
    val lastOperator: String? = null,
    val lastOperationEpochMillis: Long? = null,
)

internal enum class WorkflowRecordType(val label: String) {
    LOGIN("Login"),
    PICKUP_REQUESTED("Pickup requested"),
    PICKUP_COMPLETED("Key taken"),
    RETURN_REQUESTED("Return requested"),
    RETURN_COMPLETED("Key returned"),
    FOB_ENROLLED("Fob enrolled"),
    FOB_ENROLLMENT_DENIED("Fob enrolment denied"),
    ADMINISTRATION("Administration"),
    EVENT("Event"),
}

internal data class WorkflowRecord(
    val id: String,
    val type: WorkflowRecordType,
    val title: String,
    val detail: String,
    val timestampEpochMillis: Long,
)

internal data class WorkflowAppointment(
    val id: String,
    val keyId: String,
    val userId: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

internal data class TerminalWorkflowSettings(
    val requireReturnAuthentication: Boolean = false,
    val showHomeRealTimeStatus: Boolean = true,
    val pickupVideoRecording: Boolean = false,
    val returnVideoRecording: Boolean = false,
    val loginPhotoUpload: Boolean = false,
    val autoSyncEnabled: Boolean = false,
)

internal data class PendingPickup(
    val keyId: String,
    val requestedByUserId: String,
    val eventLabel: String,
    val note: String,
)

internal enum class KeyPresentation {
    LAYOUT,
    LIST,
}

internal object TerminalWorkflowSeed {
    fun users(): List<WorkflowUser> = listOf(
        WorkflowUser(
            id = "usr_operator_amina",
            displayName = "Amina Rahman",
            role = WorkflowUserRole.REGULAR,
            credentialSummary = "NFC card and fingerprint enrolled",
            multiAuthenticationGroup = "Workshop verifier",
        ),
        WorkflowUser(
            id = "usr_operator_faiz",
            displayName = "Faiz Hakim",
            role = WorkflowUserRole.REGULAR,
            credentialSummary = "NFC card enrolled",
            multiAuthenticationGroup = "Workshop verifier",
        ),
        WorkflowUser(
            id = "usr_admin_local",
            displayName = "Local Administrator",
            role = WorkflowUserRole.ADMINISTRATOR,
            credentialSummary = "Administrator credential configured",
        ),
    )

    fun keys(): List<WorkflowKey> = listOf(
        WorkflowKey(
            id = "key_fleet_van",
            displayName = "Fleet Van Key",
            nodeAddress = 1,
            status = WorkflowKeyStatus.PRESENT,
            permittedUserIds = setOf("usr_operator_amina", "usr_operator_faiz"),
            fobEnrolled = false,
            overdueMinutes = 240,
            lastOperator = "Amina Rahman",
            lastOperationEpochMillis = System.currentTimeMillis() - 86_400_000L,
        ),
        WorkflowKey(
            id = "key_workshop_master",
            displayName = "Workshop Master Key",
            nodeAddress = 2,
            status = WorkflowKeyStatus.PRESENT,
            permittedUserIds = setOf("usr_operator_amina"),
            fobEnrolled = false,
            overdueMinutes = 120,
            nodeType = KeyNodeType.SMALL_DOOR_WITH_KEY,
            lastOperator = "Faiz Hakim",
            lastOperationEpochMillis = System.currentTimeMillis() - 172_800_000L,
        ),
        WorkflowKey(
            id = "key_generator_room",
            displayName = "Generator Room Key",
            nodeAddress = 3,
            status = WorkflowKeyStatus.PRESENT,
            permittedUserIds = setOf("usr_operator_amina", "usr_operator_faiz"),
            requiresDualAuthentication = true,
            fobEnrolled = false,
            overdueMinutes = 60,
            lastOperator = "Local Administrator",
            lastOperationEpochMillis = System.currentTimeMillis() - 3_600_000L,
        ),
        WorkflowKey(
            id = "key_meeting_room",
            displayName = "Meeting Room Key",
            nodeAddress = 4,
            status = WorkflowKeyStatus.OUT,
            permittedUserIds = setOf("usr_operator_amina", "usr_operator_faiz"),
            fobEnrolled = false,
            lastOperator = "Faiz Hakim",
            lastOperationEpochMillis = System.currentTimeMillis() - 1_800_000L,
        ),
        WorkflowKey(
            id = "key_spare_cabinet",
            displayName = "Spare Cabinet Key",
            nodeAddress = 5,
            status = WorkflowKeyStatus.UNREGISTERED,
            permittedUserIds = emptySet(),
            fobEnrolled = false,
        ),
        WorkflowKey(
            id = "key_pump_room",
            displayName = "Pump Room Key",
            nodeAddress = 6,
            status = WorkflowKeyStatus.PRESENT,
            permittedUserIds = setOf("usr_operator_faiz"),
            fobEnrolled = false,
            overdueMinutes = 90,
        ),
    )

    fun records(): List<WorkflowRecord> = listOf(
        WorkflowRecord(
            id = "record_seed_1",
            type = WorkflowRecordType.RETURN_COMPLETED,
            title = "Meeting Room Key returned",
            detail = "Last recorded operator: Faiz Hakim.",
            timestampEpochMillis = System.currentTimeMillis() - 3_600_000L,
        ),
        WorkflowRecord(
            id = "record_seed_2",
            type = WorkflowRecordType.LOGIN,
            title = "Administrator login",
            detail = "Local terminal workflow preview.",
            timestampEpochMillis = System.currentTimeMillis() - 7_200_000L,
        ),
    )

    fun appointments(): List<WorkflowAppointment> = listOf(
        WorkflowAppointment(
            id = "appointment_seed_1",
            keyId = "key_pump_room",
            userId = "usr_operator_faiz",
            startEpochMillis = System.currentTimeMillis() + 3_600_000L,
            endEpochMillis = System.currentTimeMillis() + 10_800_000L,
        ),
    )
}