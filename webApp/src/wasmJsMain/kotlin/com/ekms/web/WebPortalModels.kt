package com.ekms.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Browser-only view state for the supplier-aligned Website workflow.
 *
 * This is deliberately a local preview store. The backend integration points
 * are documented in shared/api/ApiContracts.kt and docs/WEB_PORTAL_WORKFLOW_HANDOVER.md.
 * No value in this file opens a terminal serial port or stores a raw fob UID.
 */
internal class WebPortalStore {
    var route by mutableStateOf(WebRoute.DASHBOARD)
    var signedIn by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var dialogKind by mutableStateOf<WebDialogKind?>(null)
    var activePersonId by mutableStateOf("person-001")
    var activeAppointmentId by mutableStateOf("appointment-001")
    var selectedTerminalId by mutableStateOf("terminal-kl-01")
    var openConflictCount by mutableStateOf(1)

    private var nextLocalId = 100

    val sites = mutableStateListOf(
        PortalSite(
            id = "site-kl",
            name = "Kuala Lumpur HQ",
            province = "Kuala Lumpur",
            city = "Kuala Lumpur",
            parentUnit = "Cavotec Malaysia",
        ),
        PortalSite(
            id = "site-jb",
            name = "Johor Service Hub",
            province = "Johor",
            city = "Johor Bahru",
            parentUnit = "Cavotec Malaysia",
        ),
    )

    val terminals = mutableStateListOf(
        PortalTerminal(
            id = "terminal-kl-01",
            siteId = "site-kl",
            name = "HQ Main Cabinet",
            deviceUid = "F7G18P-KL-001",
            nodeLayout = NodeLayoutType.A_STANDARD_KEY_SLOT,
            nodeCount = 48,
            keyReturnAuthentication = true,
            status = PortalTerminalStatus.ONLINE,
            lastSyncLabel = "Synced",
        ),
        PortalTerminal(
            id = "terminal-jb-01",
            siteId = "site-jb",
            name = "Service Cabinet",
            deviceUid = "F7G18P-JB-001",
            nodeLayout = NodeLayoutType.B_COMPACT_DOOR_WITH_SLOT,
            nodeCount = 24,
            keyReturnAuthentication = false,
            status = PortalTerminalStatus.SYNC_PENDING,
            lastSyncLabel = "Configuration pending",
        ),
    )

    val people = mutableStateListOf(
        PortalPerson(
            id = "person-001",
            displayName = "Aina Rahman",
            employeeId = "EMP-1001",
            siteId = "site-kl",
            userGroup = "Operations Team",
            accessWindow = "Mon–Fri, 08:00–18:00",
            accountStatus = PortalAccountStatus.ACTIVE,
            credentialSummary = "NFC card enrolled",
        ),
        PortalPerson(
            id = "person-002",
            displayName = "Faizal Ismail",
            employeeId = "EMP-1002",
            siteId = "site-jb",
            userGroup = "Field Service",
            accessWindow = "Daily, 08:00–20:00",
            accountStatus = PortalAccountStatus.ACTIVE,
            credentialSummary = "Fingerprint enrolled",
        ),
        PortalPerson(
            id = "person-003",
            displayName = "Vendor Visitor",
            employeeId = "VND-0007",
            siteId = "site-kl",
            userGroup = "Vendor",
            accessWindow = "Pending approval",
            accountStatus = PortalAccountStatus.PENDING_APPROVAL,
            credentialSummary = "No active credential",
        ),
    )

    val keys = mutableStateListOf(
        PortalKey(
            id = "key-001",
            siteId = "site-kl",
            terminalId = "terminal-kl-01",
            displayName = "Main Gate Key",
            location = "Node 01",
            timeLimit = "Always available",
            keyGroup = "Security Keys",
            fobEnrollmentStatus = FobEnrollmentStatus.ENROLLED,
            availability = KeyAvailability.AVAILABLE,
        ),
        PortalKey(
            id = "key-002",
            siteId = "site-kl",
            terminalId = "terminal-kl-01",
            displayName = "Forklift Key A",
            location = "Node 12",
            timeLimit = "Mon–Fri, 08:00–18:00",
            keyGroup = "Operations Keys",
            fobEnrollmentStatus = FobEnrollmentStatus.ENROLLED,
            availability = KeyAvailability.TAKEN,
        ),
        PortalKey(
            id = "key-003",
            siteId = "site-jb",
            terminalId = "terminal-jb-01",
            displayName = "Service Room Key",
            location = "Node 04",
            timeLimit = "Daily, 08:00–20:00",
            keyGroup = "Maintenance Keys",
            fobEnrollmentStatus = FobEnrollmentStatus.NOT_ENROLLED,
            availability = KeyAvailability.AVAILABLE,
        ),
    )

    val accessGrants = mutableStateListOf(
        PortalAccessGrant("grant-001", "person-001", "key-001", "No expiry"),
        PortalAccessGrant("grant-002", "person-001", "key-002", "31 Dec 2026"),
        PortalAccessGrant("grant-003", "person-002", "key-003", "No expiry"),
    )

    val eventDefinitions = mutableStateListOf(
        PortalEventDefinition("event-001", "site-kl", "Shift handover", "EVT-100", "Required remark"),
        PortalEventDefinition("event-002", "site-jb", "External service", "EVT-200", "Appointment linked"),
    )

    val schedules = mutableStateListOf(
        PortalSchedule("schedule-001", "site-kl", "Operations hours", "Weekly", "Mon–Fri, 08:00–18:00"),
        PortalSchedule("schedule-002", "site-jb", "Service standby", "Daily", "08:00–20:00"),
    )

    val userGroups = mutableStateListOf(
        PortalGroup("user-group-001", "Operations Team", "UG-001", "site-kl"),
        PortalGroup("user-group-002", "Field Service", "UG-002", "site-jb"),
        PortalGroup("user-group-003", "Vendor", "UG-003", "site-kl"),
    )

    val keyGroups = mutableStateListOf(
        PortalGroup("key-group-001", "Security Keys", "KG-001", "site-kl"),
        PortalGroup("key-group-002", "Operations Keys", "KG-002", "site-kl"),
        PortalGroup("key-group-003", "Maintenance Keys", "KG-003", "site-jb"),
    )

    val multiAuthRules = mutableStateListOf(
        PortalMultiAuthRule(
            id = "rule-001",
            siteId = "site-kl",
            primaryUserGroup = "Operations Team",
            assistantGroupOne = "Security Team",
            assistantGroupTwo = "",
            keyGroup = "Security Keys",
        ),
    )

    val appointments = mutableStateListOf(
        PortalAppointment(
            id = "appointment-001",
            siteId = "site-kl",
            terminalId = "terminal-kl-01",
            personId = "person-003",
            keyIds = setOf("key-001"),
            pickupWindow = "25 Jul 2026, 10:00–12:00",
            reason = "Scheduled contractor access",
            status = AppointmentStatus.PENDING,
        ),
    )

    val appointmentReasons = mutableStateListOf(
        PortalAppointmentReason("reason-001", "site-kl", "Scheduled contractor access"),
        PortalAppointmentReason("reason-002", "site-jb", "Emergency service call"),
    )

    val keyRecords = mutableStateListOf(
        PortalKeyRecord("record-001", "24 Jul 2026, 09:15", "HQ Main Cabinet", "Forklift Key A", "Aina Rahman", "Taken", "Verified by terminal"),
        PortalKeyRecord("record-002", "23 Jul 2026, 17:42", "Service Cabinet", "Service Room Key", "Faizal Ismail", "Returned", "Return accepted"),
    )

    val systemLogs = mutableStateListOf(
        PortalLog("sys-001", "24 Jul 2026, 09:00", "Super Admin", "Personnel record updated", "Kuala Lumpur HQ"),
        PortalLog("sys-002", "23 Jul 2026, 16:20", "Super Admin", "Terminal configuration downloaded", "Service Cabinet"),
    )

    val equipmentLogs = mutableStateListOf(
        PortalLog("eq-001", "24 Jul 2026, 09:15", "HQ Main Cabinet", "Key take physically confirmed", "Node 12"),
        PortalLog("eq-002", "23 Jul 2026, 17:42", "Service Cabinet", "Key return physically confirmed", "Node 04"),
    )

    val recycleBin = mutableStateListOf<PortalDeletedRecord>()

    fun openDialog(kind: WebDialogKind) {
        dialogKind = kind
    }

    fun create(kind: WebDialogKind, values: List<String>) {
        when (kind) {
            WebDialogKind.ADD_UNIT -> {
                val site = PortalSite(
                    id = nextId("site"),
                    name = field(values, 0, "New Unit"),
                    province = field(values, 1, "Not set"),
                    city = field(values, 2, "Not set"),
                    parentUnit = field(values, 3, "Cavotec Malaysia"),
                )
                sites.add(site)
                notice = "${site.name} was added to this local Website preview. Production will call POST /v1/admin/sites."
            }

            WebDialogKind.ADD_TERMINAL -> {
                val terminal = PortalTerminal(
                    id = nextId("terminal"),
                    siteId = siteIdFor(field(values, 1, sites.firstOrNull()?.name.orEmpty())),
                    name = field(values, 0, "New Cabinet"),
                    deviceUid = field(values, 2, "Device UID pending"),
                    nodeLayout = NodeLayoutType.fromInput(field(values, 3, "A")),
                    nodeCount = field(values, 4, "24").toIntOrNull()?.coerceIn(1, 255) ?: 24,
                    keyReturnAuthentication = !field(values, 5, "Enabled").equals("disabled", ignoreCase = true),
                    status = PortalTerminalStatus.SYNC_PENDING,
                    lastSyncLabel = "Awaiting first download",
                )
                terminals.add(terminal)
                selectedTerminalId = terminal.id
                notice = "${terminal.name} was configured locally. The Website records configuration only; the terminal performs physical cabinet I/O."
            }

            WebDialogKind.ADD_PERSON -> {
                val person = PortalPerson(
                    id = nextId("person"),
                    displayName = field(values, 0, "New person"),
                    employeeId = field(values, 2, "Employee ID pending"),
                    siteId = siteIdFor(field(values, 1, sites.firstOrNull()?.name.orEmpty())),
                    userGroup = field(values, 3, "Unassigned"),
                    accessWindow = field(values, 4, "Not configured"),
                    accountStatus = PortalAccountStatus.PENDING_APPROVAL,
                    credentialSummary = "Credential not enrolled",
                )
                people.add(person)
                activePersonId = person.id
                notice = "${person.displayName} is pending Super Admin approval. The browser does not persist the submitted password or biometric data."
            }

            WebDialogKind.ADD_KEY -> {
                val terminal = terminalFor(field(values, 1, terminals.firstOrNull()?.name.orEmpty()))
                val key = PortalKey(
                    id = nextId("key"),
                    siteId = terminal?.siteId ?: sites.firstOrNull()?.id.orEmpty(),
                    terminalId = terminal?.id ?: terminals.firstOrNull()?.id.orEmpty(),
                    displayName = field(values, 2, "New key"),
                    location = field(values, 3, "Node not assigned"),
                    timeLimit = field(values, 4, "Not configured"),
                    keyGroup = field(values, 5, "Unassigned"),
                    fobEnrollmentStatus = FobEnrollmentStatus.NOT_ENROLLED,
                    availability = KeyAvailability.UNAVAILABLE,
                )
                keys.add(key)
                notice = "${key.displayName} was created as an un-enrolled logical key. Physical fob enrolment remains a Super Admin action on the Terminal."
            }

            WebDialogKind.ADD_EVENT -> {
                val event = PortalEventDefinition(
                    id = nextId("event"),
                    siteId = siteIdFor(field(values, 0, sites.firstOrNull()?.name.orEmpty())),
                    name = field(values, 1, "New event"),
                    code = field(values, 2, "EVT-${nextLocalId}"),
                    type = field(values, 3, "Optional remark"),
                )
                eventDefinitions.add(event)
                notice = "${event.name} was added to the local preview."
            }

            WebDialogKind.ADD_SCHEDULE -> {
                val schedule = PortalSchedule(
                    id = nextId("schedule"),
                    siteId = siteIdFor(field(values, 0, sites.firstOrNull()?.name.orEmpty())),
                    name = field(values, 1, "New schedule"),
                    frequency = field(values, 2, "Weekly"),
                    timeWindow = field(values, 3, "Not configured"),
                )
                schedules.add(schedule)
                notice = "${schedule.name} was added to the local preview."
            }

            WebDialogKind.ADD_USER_GROUP -> {
                val group = PortalGroup(
                    id = nextId("user-group"),
                    name = field(values, 0, "New user group"),
                    number = field(values, 1, "UG-${nextLocalId}"),
                    siteId = siteIdFor(field(values, 2, sites.firstOrNull()?.name.orEmpty())),
                )
                userGroups.add(group)
                notice = "${group.name} was added to the local preview."
            }

            WebDialogKind.ADD_KEY_GROUP -> {
                val group = PortalGroup(
                    id = nextId("key-group"),
                    name = field(values, 0, "New key group"),
                    number = field(values, 1, "KG-${nextLocalId}"),
                    siteId = siteIdFor(field(values, 2, sites.firstOrNull()?.name.orEmpty())),
                )
                keyGroups.add(group)
                notice = "${group.name} was added to the local preview."
            }

            WebDialogKind.ADD_MULTI_AUTH_RULE -> {
                val rule = PortalMultiAuthRule(
                    id = nextId("multi-auth"),
                    siteId = siteIdFor(field(values, 0, sites.firstOrNull()?.name.orEmpty())),
                    primaryUserGroup = field(values, 1, "Primary group"),
                    assistantGroupOne = field(values, 2, "Assistant group 1"),
                    assistantGroupTwo = field(values, 3, ""),
                    keyGroup = field(values, 4, "Key group"),
                )
                multiAuthRules.add(rule)
                notice = "Multi-authentication rule added to the local preview."
            }

            WebDialogKind.ADD_APPOINTMENT -> {
                val terminal = terminalFor(field(values, 1, terminals.firstOrNull()?.name.orEmpty()))
                val person = people.firstOrNull { it.displayName.equals(values.getOrNull(3)?.trim(), ignoreCase = true) }
                    ?: people.firstOrNull()
                val key = keys.firstOrNull { it.displayName.equals(values.getOrNull(2)?.trim(), ignoreCase = true) }
                    ?: keys.firstOrNull()
                val appointment = PortalAppointment(
                    id = nextId("appointment"),
                    siteId = terminal?.siteId ?: sites.firstOrNull()?.id.orEmpty(),
                    terminalId = terminal?.id ?: terminals.firstOrNull()?.id.orEmpty(),
                    personId = person?.id.orEmpty(),
                    keyIds = key?.let { setOf(it.id) }.orEmpty(),
                    pickupWindow = field(values, 4, "Time not set"),
                    reason = field(values, 5, "No reason supplied"),
                    status = AppointmentStatus.PENDING,
                )
                appointments.add(appointment)
                activeAppointmentId = appointment.id
                notice = "Appointment created and awaiting review. Backend approval must create the temporary, exact-key authorization decision."
            }

            WebDialogKind.ADD_APPOINTMENT_REASON -> {
                val reason = PortalAppointmentReason(
                    id = nextId("appointment-reason"),
                    siteId = siteIdFor(field(values, 0, sites.firstOrNull()?.name.orEmpty())),
                    name = field(values, 1, "New appointment reason"),
                )
                appointmentReasons.add(reason)
                notice = "Appointment reason added to the local preview."
            }
        }
        dialogKind = null
    }

    fun grantKey(personId: String, keyId: String) {
        if (accessGrants.any { it.personId == personId && it.keyId == keyId }) {
            notice = "That exact key is already authorized for the selected person."
            return
        }
        accessGrants.add(PortalAccessGrant(nextId("grant"), personId, keyId, "No expiry"))
        notice = "Key permission prepared locally. Production requires a revision-safe POST /v1/admin/access-grants."
    }

    fun revokeGrant(grant: PortalAccessGrant) {
        accessGrants.remove(grant)
        notice = "The local grant was removed. The production backend must record an immutable audit event."
    }

    fun reviewAppointment(appointmentId: String, status: AppointmentStatus) {
        val index = appointments.indexOfFirst { it.id == appointmentId }
        if (index >= 0) {
            appointments[index] = appointments[index].copy(status = status)
            notice = when (status) {
                AppointmentStatus.APPROVED -> "Appointment approved locally. Backend must issue exact-key, time-bounded authorization."
                AppointmentStatus.REJECTED -> "Appointment rejected locally. Backend must retain the reviewer and reason."
                AppointmentStatus.PENDING -> "Appointment returned to pending review."
            }
        }
    }

    fun addAppointmentKey(appointmentId: String, keyId: String) {
        val index = appointments.indexOfFirst { it.id == appointmentId }
        if (index >= 0) {
            appointments[index] = appointments[index].copy(keyIds = appointments[index].keyIds + keyId)
            notice = "Exact key added to the appointment permission preview."
        }
    }

    fun archiveSite(site: PortalSite) {
        if (terminals.any { it.siteId == site.id }) {
            notice = "Archive the unit's terminals first. The Website will not perform a hidden cascade delete."
            return
        }
        sites.remove(site)
        recycleBin.add(PortalDeletedRecord.Site(site))
        notice = "${site.name} moved to the Super Admin Recycle Bin for ${RECYCLE_RETENTION_LABEL}."
    }

    fun archiveTerminal(terminal: PortalTerminal) {
        if (keys.any { it.terminalId == terminal.id }) {
            notice = "Archive or move the terminal's assigned keys first. Cabinet dependencies cannot be silently deleted."
            return
        }
        terminals.remove(terminal)
        recycleBin.add(PortalDeletedRecord.Terminal(terminal))
        notice = "${terminal.name} moved to the Super Admin Recycle Bin."
    }

    fun archivePerson(person: PortalPerson) {
        people.remove(person)
        recycleBin.add(PortalDeletedRecord.Person(person))
        notice = "${person.displayName} moved to the Super Admin Recycle Bin."
    }

    fun archiveKey(key: PortalKey) {
        keys.remove(key)
        recycleBin.add(PortalDeletedRecord.Key(key))
        notice = "${key.displayName} moved to the Super Admin Recycle Bin."
    }

    fun restore(record: PortalDeletedRecord) {
        when (record) {
            is PortalDeletedRecord.Site -> sites.add(record.site)
            is PortalDeletedRecord.Terminal -> terminals.add(record.terminal)
            is PortalDeletedRecord.Person -> people.add(record.person)
            is PortalDeletedRecord.Key -> keys.add(record.key)
        }
        recycleBin.remove(record)
        notice = "${record.label} restored locally. Production restore requires Super Admin authorization and an audit event."
    }

    fun purge(record: PortalDeletedRecord) {
        recycleBin.remove(record)
        notice = "${record.label} was permanently cleared from the local preview. Historical audit events remain retained."
    }

    fun siteName(siteId: String): String = sites.firstOrNull { it.id == siteId }?.name ?: "Unassigned unit"

    fun terminalName(terminalId: String): String = terminals.firstOrNull { it.id == terminalId }?.name ?: "Unassigned terminal"

    fun personName(personId: String): String = people.firstOrNull { it.id == personId }?.displayName ?: "Unassigned person"

    fun keyName(keyId: String): String = keys.firstOrNull { it.id == keyId }?.displayName ?: "Unassigned key"

    fun terminalFor(name: String): PortalTerminal? = terminals.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    private fun siteIdFor(name: String): String =
        sites.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id ?: sites.firstOrNull()?.id.orEmpty()

    private fun nextId(prefix: String): String = "$prefix-${nextLocalId++}"

    private fun field(values: List<String>, index: Int, fallback: String): String =
        values.getOrNull(index)?.trim().takeUnless { it.isNullOrBlank() } ?: fallback
}

internal const val RECYCLE_RETENTION_LABEL = "60 days"

internal enum class WebRoute(val label: String) {
    DASHBOARD("Dashboard"),
    UNITS("Unit Settings"),
    TERMINALS("Terminal Settings"),
    PERSONNEL("Personnel Management"),
    KEYS("Key Settings"),
    PERMISSIONS("Permission Settings"),
    EVENTS("Event Setup"),
    SCHEDULES("Schedule Settings"),
    MULTI_AUTH_RULES("Multi-authentication Rules"),
    USER_GROUPS("User Groups"),
    KEY_GROUPS("Key Groups"),
    DATA_SYNC("Data Synchronization"),
    KEY_RECORDS("Pickup & Return Records"),
    OPERATION_LOGS("Operation Log"),
    APPOINTMENTS("Appointment Authorization"),
    APPOINTMENT_REASONS("Appointment Reason Settings"),
    APPOINTMENT_PERMISSIONS("Appointment Permission Settings"),
    SYSTEM_LOGS("System Operation Log"),
    EQUIPMENT_LOGS("Equipment Operation Log"),
    RECYCLE_BIN("Recycle Bin"),
}

internal enum class WebDialogKind(
    val title: String,
    val submitLabel: String,
    val fields: List<String>,
) {
    ADD_UNIT(
        title = "Add unit",
        submitLabel = "Save unit",
        fields = listOf("Unit name", "Province / state", "City", "Superior unit"),
    ),
    ADD_TERMINAL(
        title = "Add Android terminal cabinet",
        submitLabel = "Save terminal",
        fields = listOf(
            "Terminal / cabinet name",
            "Affiliated unit",
            "Unique device ID",
            "Node layout (A, B or C)",
            "Configured node count",
            "Key return authentication (Enabled / Disabled)",
        ),
    ),
    ADD_PERSON(
        title = "Add personnel",
        submitLabel = "Create pending account",
        fields = listOf(
            "Name",
            "Affiliated unit",
            "Employee ID",
            "Multi-authentication personnel group",
            "Access time / schedule",
            "Initial password (sent to backend only)",
        ),
    ),
    ADD_KEY(
        title = "Add logical key",
        submitLabel = "Save key",
        fields = listOf("Affiliated unit", "Terminal cabinet", "Key name", "Location / node", "Time limit", "Key group"),
    ),
    ADD_EVENT(
        title = "Add event",
        submitLabel = "Save event",
        fields = listOf("Affiliated unit", "Event name", "Event number", "Event type / requirement"),
    ),
    ADD_SCHEDULE(
        title = "Add schedule",
        submitLabel = "Save schedule",
        fields = listOf("Affiliated unit", "Schedule name", "Frequency (Daily / Weekly / Monthly)", "Time period"),
    ),
    ADD_USER_GROUP(
        title = "Add user group",
        submitLabel = "Save group",
        fields = listOf("Group name", "Group number", "Affiliated unit"),
    ),
    ADD_KEY_GROUP(
        title = "Add key group",
        submitLabel = "Save group",
        fields = listOf("Group name", "Group number", "Affiliated unit"),
    ),
    ADD_MULTI_AUTH_RULE(
        title = "Add multi-authentication rule",
        submitLabel = "Save rule",
        fields = listOf(
            "Affiliated unit",
            "Primary personnel group",
            "Assistant personnel group 1",
            "Assistant personnel group 2 (optional)",
            "Multi-authentication key group",
        ),
    ),
    ADD_APPOINTMENT(
        title = "Add appointment authorization",
        submitLabel = "Submit for review",
        fields = listOf(
            "Affiliated unit",
            "Terminal cabinet",
            "Key name",
            "Personnel name",
            "Pickup date and time",
            "Appointment reason",
        ),
    ),
    ADD_APPOINTMENT_REASON(
        title = "Add appointment reason",
        submitLabel = "Save reason",
        fields = listOf("Affiliated unit", "Reason name"),
    ),
}

internal data class PortalSite(
    val id: String,
    val name: String,
    val province: String,
    val city: String,
    val parentUnit: String,
)

internal data class PortalTerminal(
    val id: String,
    val siteId: String,
    val name: String,
    val deviceUid: String,
    val nodeLayout: NodeLayoutType,
    val nodeCount: Int,
    val keyReturnAuthentication: Boolean,
    val status: PortalTerminalStatus,
    val lastSyncLabel: String,
)

internal enum class NodeLayoutType(val label: String) {
    A_STANDARD_KEY_SLOT("A · Standard key slot"),
    B_COMPACT_DOOR_WITH_SLOT("B · Compact door with node key slot"),
    C_COMPACT_DOOR_ONLY("C · Compact door without node key slot");

    companion object {
        fun fromInput(value: String): NodeLayoutType = when (value.trim().uppercase().firstOrNull()) {
            'B' -> B_COMPACT_DOOR_WITH_SLOT
            'C' -> C_COMPACT_DOOR_ONLY
            else -> A_STANDARD_KEY_SLOT
        }
    }
}

internal enum class PortalTerminalStatus(val label: String) {
    ONLINE("Online"),
    OFFLINE("Offline"),
    SYNC_PENDING("Sync pending"),
}

internal data class PortalPerson(
    val id: String,
    val displayName: String,
    val employeeId: String,
    val siteId: String,
    val userGroup: String,
    val accessWindow: String,
    val accountStatus: PortalAccountStatus,
    val credentialSummary: String,
)

internal enum class PortalAccountStatus(val label: String) {
    ACTIVE("Active"),
    PENDING_APPROVAL("Pending approval"),
    DISABLED("Disabled"),
}

internal data class PortalKey(
    val id: String,
    val siteId: String,
    val terminalId: String,
    val displayName: String,
    val location: String,
    val timeLimit: String,
    val keyGroup: String,
    val fobEnrollmentStatus: FobEnrollmentStatus,
    val availability: KeyAvailability,
)

internal enum class FobEnrollmentStatus(val label: String) {
    NOT_ENROLLED("Fob enrolment required"),
    ENROLLED("Fob enrolled"),
    REVOKED("Fob revoked"),
}

internal enum class KeyAvailability(val label: String) {
    AVAILABLE("Available"),
    TAKEN("Taken"),
    UNAVAILABLE("Unavailable"),
}

internal data class PortalAccessGrant(
    val id: String,
    val personId: String,
    val keyId: String,
    val validUntil: String,
)

internal data class PortalEventDefinition(
    val id: String,
    val siteId: String,
    val name: String,
    val code: String,
    val type: String,
)

internal data class PortalSchedule(
    val id: String,
    val siteId: String,
    val name: String,
    val frequency: String,
    val timeWindow: String,
)

internal data class PortalGroup(
    val id: String,
    val name: String,
    val number: String,
    val siteId: String,
)

internal data class PortalMultiAuthRule(
    val id: String,
    val siteId: String,
    val primaryUserGroup: String,
    val assistantGroupOne: String,
    val assistantGroupTwo: String,
    val keyGroup: String,
)

internal data class PortalAppointment(
    val id: String,
    val siteId: String,
    val terminalId: String,
    val personId: String,
    val keyIds: Set<String>,
    val pickupWindow: String,
    val reason: String,
    val status: AppointmentStatus,
)

internal enum class AppointmentStatus(val label: String) {
    PENDING("Pending review"),
    APPROVED("Approved"),
    REJECTED("Rejected"),
}

internal data class PortalAppointmentReason(
    val id: String,
    val siteId: String,
    val name: String,
)

internal data class PortalKeyRecord(
    val id: String,
    val occurredAt: String,
    val terminalName: String,
    val keyName: String,
    val personName: String,
    val status: String,
    val detail: String,
)

internal data class PortalLog(
    val id: String,
    val occurredAt: String,
    val actorOrTerminal: String,
    val action: String,
    val scope: String,
)

internal sealed interface PortalDeletedRecord {
    val label: String
    val typeLabel: String

    data class Site(val site: PortalSite) : PortalDeletedRecord {
        override val label: String = site.name
        override val typeLabel: String = "Unit"
    }

    data class Terminal(val terminal: PortalTerminal) : PortalDeletedRecord {
        override val label: String = terminal.name
        override val typeLabel: String = "Terminal"
    }

    data class Person(val person: PortalPerson) : PortalDeletedRecord {
        override val label: String = person.displayName
        override val typeLabel: String = "Personnel"
    }

    data class Key(val key: PortalKey) : PortalDeletedRecord {
        override val label: String = key.displayName
        override val typeLabel: String = "Key"
    }
}