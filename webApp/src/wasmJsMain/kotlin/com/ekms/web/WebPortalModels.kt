package com.ekms.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ekms.shared.domain.AccessGrant
import com.ekms.shared.domain.KeyDraft
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.KeySlotAccessPolicy
import com.ekms.shared.domain.KeySlotDraft
import com.ekms.shared.domain.LifecycleMetadata
import com.ekms.shared.domain.ManagedKey
import com.ekms.shared.domain.ManagedTerminalOption
import com.ekms.shared.domain.RecordLifecycle
import kotlinx.datetime.Clock

/**
 * Browser-only view state for the supplier-aligned Website workflow.
 *
 * This is deliberately a local preview store. The backend integration points
 * are documented in shared/api/ApiContracts.kt and docs/WEB_PORTAL_WORKFLOW_HANDOVER.md.
 * No value in this file opens a terminal serial port or stores a raw fob UID.
 *
 * Keys, cabinet slots and access grants use the shared domain model
 * (shared/domain/AdminModels.kt, shared/domain/KeySlotManagement.kt) directly,
 * including its Box/Node address validation and soft-delete lifecycle, so the
 * same records and rules the backend will enforce are already exercised here.
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
            boxAddress = 1,
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
            boxAddress = 1,
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
        ManagedKey(
            id = "key-001",
            siteId = "site-kl",
            displayName = "Main Gate Key",
            lifecycle = activeLifecycle(DEMO_CREATED_AT),
        ),
        ManagedKey(
            id = "key-002",
            siteId = "site-kl",
            displayName = "Forklift Key A",
            lifecycle = activeLifecycle(DEMO_CREATED_AT),
        ),
        ManagedKey(
            id = "key-003",
            siteId = "site-jb",
            displayName = "Service Room Key",
            lifecycle = activeLifecycle(DEMO_CREATED_AT),
        ),
    )

    val keySlots = mutableStateListOf(
        KeySlot(
            id = "slot-001",
            terminalId = "terminal-kl-01",
            nodeAddress = 1,
            managedKeyId = "key-001",
            lifecycle = activeLifecycle(DEMO_CREATED_AT),
        ),
        KeySlot(
            id = "slot-002",
            terminalId = "terminal-kl-01",
            nodeAddress = 12,
            managedKeyId = "key-002",
            lifecycle = activeLifecycle(DEMO_CREATED_AT),
        ),
        KeySlot(
            id = "slot-003",
            terminalId = "terminal-jb-01",
            nodeAddress = 4,
            managedKeyId = "key-003",
            lifecycle = activeLifecycle(DEMO_CREATED_AT),
        ),
    )

    /** Supplier-workflow fields not yet modeled in the shared domain (see Key Groups / Schedules). */
    val keyExtras = mutableStateMapOf(
        "key-001" to WebKeyExtra(timeLimit = "Always available", keyGroup = "Security Keys"),
        "key-002" to WebKeyExtra(timeLimit = "Mon–Fri, 08:00–18:00", keyGroup = "Operations Keys"),
        "key-003" to WebKeyExtra(timeLimit = "Daily, 08:00–20:00", keyGroup = "Maintenance Keys"),
    )

    val accessGrants = mutableStateListOf(
        AccessGrant(
            id = "grant-001",
            userId = "person-001",
            siteId = "site-kl",
            keyIds = setOf("key-001"),
            lifecycle = activeLifecycle(DEMO_CREATED_AT),
        ),
        AccessGrant(
            id = "grant-002",
            userId = "person-001",
            siteId = "site-kl",
            keyIds = setOf("key-002"),
            validUntilEpochMillis = KeySlotAccessPolicy.validUntilEpochMillis("2026-12-31"),
            lifecycle = activeLifecycle(DEMO_CREATED_AT),
        ),
        AccessGrant(
            id = "grant-003",
            userId = "person-002",
            siteId = "site-jb",
            keyIds = setOf("key-003"),
            lifecycle = activeLifecycle(DEMO_CREATED_AT),
        ),
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
        var closeDialog = true
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
                    boxAddress = 1,
                    nodeLayout = NodeLayoutType.fromInput(field(values, 3, "A")),
                    nodeCount = field(values, 4, "24").toIntOrNull()?.coerceIn(1, 255) ?: 24,
                    keyReturnAuthentication = !field(values, 5, "Enabled").equals("disabled", ignoreCase = true),
                    status = PortalTerminalStatus.SYNC_PENDING,
                    lastSyncLabel = "Awaiting first download",
                )
                terminals.add(terminal)
                selectedTerminalId = terminal.id
                notice = "${terminal.name} was configured locally at Box Address ${terminal.boxAddress}. " +
                        "The Website records configuration only; the terminal performs physical cabinet I/O."
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
                if (terminal == null) {
                    notice = "Select a valid terminal cabinet before saving a key."
                    closeDialog = false
                } else {
                    val displayName = field(values, 2, "New key")
                    val nodeAddressText = field(values, 3, "")
                    val managedTerminals = terminals.map { it.toManagedTerminalOption() }
                    val activeKeySnapshot = keys.filter { it.lifecycle.state == RecordLifecycle.ACTIVE }
                    val activeSlotSnapshot = keySlots.filter { it.lifecycle.state == RecordLifecycle.ACTIVE }
                    val issues = KeySlotAccessPolicy.validateKey(
                        draft = KeyDraft(displayName, terminal.siteId),
                        knownSiteIds = sites.mapTo(linkedSetOf()) { it.id },
                    ) + KeySlotAccessPolicy.validateSlot(
                        draft = KeySlotDraft(terminal.id, nodeAddressText, ""),
                        terminals = managedTerminals,
                        activeKeys = activeKeySnapshot,
                        activeSlots = activeSlotSnapshot,
                    )
                    if (issues.isNotEmpty()) {
                        notice = issues.joinToString(" ") { it.message }
                        closeDialog = false
                    } else {
                        val timestamp = now()
                        val keyId = nextId("key")
                        keys.add(
                            ManagedKey(
                                id = keyId,
                                siteId = terminal.siteId,
                                displayName = displayName.trim(),
                                lifecycle = activeLifecycle(timestamp),
                            ),
                        )
                        keySlots.add(
                            KeySlot(
                                id = nextId("slot"),
                                terminalId = terminal.id,
                                nodeAddress = nodeAddressText.trim().toInt(),
                                managedKeyId = keyId,
                                lifecycle = activeLifecycle(timestamp),
                            ),
                        )
                        keyExtras[keyId] = WebKeyExtra(
                            timeLimit = field(values, 4, "Not configured"),
                            keyGroup = field(values, 5, "Unassigned"),
                        )
                        notice = "${displayName.trim()} saved at Box ${terminal.boxAddress} · Node ${nodeAddressText.trim()}. " +
                                "Physical fob enrolment remains a protected Terminal-only action."
                    }
                }
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
        if (closeDialog) {
            dialogKind = null
        }
    }

    fun grantKey(personId: String, keyId: String) {
        val key = keys.firstOrNull { it.id == keyId } ?: return
        val alreadyGranted = accessGrants.any {
            it.lifecycle.state == RecordLifecycle.ACTIVE && it.userId == personId && keyId in it.keyIds
        }
        if (alreadyGranted) {
            notice = "That exact key is already authorized for the selected person."
            return
        }
        accessGrants.add(
            AccessGrant(
                id = nextId("grant"),
                userId = personId,
                siteId = key.siteId,
                keyIds = setOf(keyId),
                lifecycle = activeLifecycle(now()),
            ),
        )
        notice = "Key permission prepared locally. Production requires a revision-safe POST /v1/admin/access-grants."
    }

    fun archiveAccessGrant(grant: AccessGrant) {
        val timestamp = now()
        accessGrants.replaceFirst({ it.id == grant.id }) { it.copy(lifecycle = archivedLifecycle(it.lifecycle, timestamp)) }
        notice = "Access grant for ${personName(grant.userId)} moved to the Super Admin Recycle Bin."
    }

    fun restoreAccessGrant(grant: AccessGrant) {
        val timestamp = now()
        accessGrants.replaceFirst({ it.id == grant.id }) { it.copy(lifecycle = restoredLifecycle(it.lifecycle, timestamp)) }
        notice = "Access grant for ${personName(grant.userId)} restored locally."
    }

    fun purgeAccessGrant(grant: AccessGrant) {
        val timestamp = now()
        accessGrants.replaceFirst({ it.id == grant.id }) { it.copy(lifecycle = purgedLifecycle(it.lifecycle, timestamp)) }
        notice = "Access grant permanently cleared from the local Recycle Bin."
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
        if (keySlots.any { it.terminalId == terminal.id && it.lifecycle.state == RecordLifecycle.ACTIVE }) {
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

    fun archiveKey(key: ManagedKey) {
        val hasActiveGrant = accessGrants.any {
            it.lifecycle.state == RecordLifecycle.ACTIVE && key.id in it.keyIds
        }
        if (hasActiveGrant) {
            notice = "Remove ${key.displayName} from its active access grants before moving it to the Recycle Bin."
            return
        }
        val timestamp = now()
        keys.replaceFirst({ it.id == key.id }) { it.copy(lifecycle = archivedLifecycle(it.lifecycle, timestamp)) }
        keySlots.filter { it.managedKeyId == key.id && it.lifecycle.state == RecordLifecycle.ACTIVE }.forEach { slot ->
            keySlots.replaceFirst({ it.id == slot.id }) { it.copy(lifecycle = archivedLifecycle(it.lifecycle, timestamp)) }
        }
        notice = "${key.displayName} moved to the Super Admin Recycle Bin."
    }

    fun restoreKey(key: ManagedKey) {
        val timestamp = now()
        keys.replaceFirst({ it.id == key.id }) { it.copy(lifecycle = restoredLifecycle(it.lifecycle, timestamp)) }
        keySlots.filter { it.managedKeyId == key.id && it.lifecycle.state == RecordLifecycle.RECYCLE_BIN }.forEach { slot ->
            keySlots.replaceFirst({ it.id == slot.id }) { it.copy(lifecycle = restoredLifecycle(it.lifecycle, timestamp)) }
        }
        notice = "${key.displayName} restored locally."
    }

    fun purgeKey(key: ManagedKey) {
        val timestamp = now()
        keys.replaceFirst({ it.id == key.id }) { it.copy(lifecycle = purgedLifecycle(it.lifecycle, timestamp)) }
        keySlots.filter { it.managedKeyId == key.id }.forEach { slot ->
            keySlots.replaceFirst({ it.id == slot.id }) { it.copy(lifecycle = purgedLifecycle(it.lifecycle, timestamp)) }
        }
        keyExtras.remove(key.id)
        notice = "${key.displayName} permanently cleared from the local Recycle Bin."
    }

    fun restore(record: PortalDeletedRecord) {
        when (record) {
            is PortalDeletedRecord.Site -> sites.add(record.site)
            is PortalDeletedRecord.Terminal -> terminals.add(record.terminal)
            is PortalDeletedRecord.Person -> people.add(record.person)
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

    /** The key's current active cabinet-slot mapping, if any. A key may exist without one yet. */
    fun keySlotFor(keyId: String): KeySlot? =
        keySlots.firstOrNull { it.managedKeyId == keyId && it.lifecycle.state == RecordLifecycle.ACTIVE }

    fun keyLocationLabel(keyId: String): String {
        val slot = keySlotFor(keyId) ?: return "Not assigned to a cabinet slot"
        val boxAddress = terminals.firstOrNull { it.id == slot.terminalId }?.boxAddress
        return if (boxAddress == null) "Node ${slot.nodeAddress}" else "Box $boxAddress · Node ${slot.nodeAddress}"
    }

    fun keyTerminalName(keyId: String): String = keySlotFor(keyId)?.let { terminalName(it.terminalId) } ?: "Not assigned to a terminal"

    fun terminalFor(name: String): PortalTerminal? = terminals.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    private fun siteIdFor(name: String): String =
        sites.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id ?: sites.firstOrNull()?.id.orEmpty()

    private fun nextId(prefix: String): String = "$prefix-${nextLocalId++}"

    private fun field(values: List<String>, index: Int, fallback: String): String =
        values.getOrNull(index)?.trim().takeUnless { it.isNullOrBlank() } ?: fallback

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}

private const val DEMO_CREATED_AT = 1_783_000_000_000L
private const val SUPER_ADMIN_ACTOR_ID = "usr_super_admin_demo"

private fun activeLifecycle(timestamp: Long) = LifecycleMetadata(
    createdAtEpochMillis = timestamp,
    updatedAtEpochMillis = timestamp,
)

private fun archivedLifecycle(existing: LifecycleMetadata, timestamp: Long) = existing.copy(
    state = RecordLifecycle.RECYCLE_BIN,
    updatedAtEpochMillis = timestamp,
    deletedAtEpochMillis = timestamp,
    deletedByUserId = SUPER_ADMIN_ACTOR_ID,
)

private fun restoredLifecycle(existing: LifecycleMetadata, timestamp: Long) = existing.copy(
    state = RecordLifecycle.ACTIVE,
    updatedAtEpochMillis = timestamp,
    deletedAtEpochMillis = null,
    deletedByUserId = null,
)

private fun purgedLifecycle(existing: LifecycleMetadata, timestamp: Long) = existing.copy(
    state = RecordLifecycle.PURGED,
    updatedAtEpochMillis = timestamp,
)

private fun <T> SnapshotStateList<T>.replaceFirst(predicate: (T) -> Boolean, transform: (T) -> T) {
    val index = indexOfFirst(predicate)
    if (index >= 0) this[index] = transform(this[index])
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
        fields = listOf(
            "Affiliated unit",
            "Terminal cabinet",
            "Key name",
            "Key node address (1 to terminal capacity, door node 0 not allowed)",
            "Time limit",
            "Key group",
        ),
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
    /** Cabinet protocol Box Address (1–255). One terminal owns exactly one Box Address. */
    val boxAddress: Int,
    val nodeLayout: NodeLayoutType,
    val nodeCount: Int,
    val keyReturnAuthentication: Boolean,
    val status: PortalTerminalStatus,
    val lastSyncLabel: String,
)

/** Projects this preview terminal into the shared node-address validation policy's terminal shape. */
internal fun PortalTerminal.toManagedTerminalOption(): ManagedTerminalOption = ManagedTerminalOption(
    id = id,
    siteId = siteId,
    label = "$name · Box $boxAddress",
    configuredSlotCount = nodeCount,
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

/** Supplier-workflow fields (time limit, key group) not yet part of the shared ManagedKey contract. */
internal data class WebKeyExtra(
    val timeLimit: String,
    val keyGroup: String,
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
}
