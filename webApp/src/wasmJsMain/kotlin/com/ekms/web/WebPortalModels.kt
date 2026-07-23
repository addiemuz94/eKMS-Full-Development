package com.ekms.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ekms.shared.api.AccessGrantUpsertRequest
import com.ekms.shared.api.KeySlotUpsertRequest
import com.ekms.shared.api.KeyUpsertRequest
import com.ekms.shared.api.LoginResponse
import com.ekms.shared.api.SiteUpsertRequest
import com.ekms.shared.api.TerminalUpsertRequest
import com.ekms.shared.domain.AccessGrant
import com.ekms.shared.domain.AccountStatus
import com.ekms.shared.domain.CredentialKind
import com.ekms.shared.domain.KeyDraft
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.KeySlotAccessPolicy
import com.ekms.shared.domain.KeySlotDraft
import com.ekms.shared.domain.LifecycleMetadata
import com.ekms.shared.domain.ManagedKey
import com.ekms.shared.domain.ManagedTerminalOption
import com.ekms.shared.domain.RecordLifecycle
import com.ekms.shared.domain.RecordType
import com.ekms.shared.domain.UserRole
import com.ekms.shared.policy.RecycleBinEntry
import com.ekms.shared.sync.ConflictResolutionStrategy
import com.ekms.shared.sync.SyncConflict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json

/** Browser-only view state for the supplier-aligned Website workflow. */
internal class WebPortalStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var route by mutableStateOf(WebRoute.DASHBOARD)
    var signedIn by mutableStateOf(false)
    var signedInDisplayName by mutableStateOf("Super Admin")
    var apiConnected by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var dialogKind by mutableStateOf<WebDialogKind?>(null)
    var activePersonId by mutableStateOf("person-001")
    var activeAppointmentId by mutableStateOf("appointment-001")
    var selectedTerminalId by mutableStateOf("terminal-kl-01")
    var openConflictCount by mutableStateOf(0)

    private var nextLocalId = 100

    val sites = mutableStateListOf<PortalSite>()
    val terminals = mutableStateListOf<PortalTerminal>()
    val people = mutableStateListOf<PortalPerson>()
    val keys = mutableStateListOf<ManagedKey>()
    val keySlots = mutableStateListOf<KeySlot>()
    val keyRevisions = mutableStateMapOf<String, Long>()
    val keySlotRevisions = mutableStateMapOf<String, Long>()
    val accessGrantRevisions = mutableStateMapOf<String, Long>()
    val recycleBinEntries = mutableStateListOf<RecycleBinEntry>()
    val syncConflicts = mutableStateListOf<SyncConflict>()

    /** Supplier-workflow fields not yet modeled in the shared domain (see Key Groups / Schedules). */
    val keyExtras = mutableStateMapOf(
        "key-001" to WebKeyExtra(timeLimit = "Always available", keyGroup = "Security Keys"),
        "key-002" to WebKeyExtra(timeLimit = "Mon–Fri, 08:00–18:00", keyGroup = "Operations Keys"),
        "key-003" to WebKeyExtra(timeLimit = "Daily, 08:00–20:00", keyGroup = "Maintenance Keys"),
    )

    val accessGrants = mutableStateListOf<AccessGrant>()

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

    val systemLogs = mutableStateListOf<PortalLog>()

    val equipmentLogs = mutableStateListOf<PortalLog>()

    val recycleBin = mutableStateListOf<PortalDeletedRecord>()

    fun openDialog(kind: WebDialogKind) {
        dialogKind = kind
    }

    fun onAuthenticated(login: LoginResponse) {
        signedIn = true
        signedInDisplayName = login.profile.displayName
        apiConnected = true
        BrowserSessionStore.save(
            PersistedSession(
                accessToken = login.accessToken,
                refreshToken = login.refreshToken,
                displayName = login.profile.displayName,
            ),
        )
        notice = "Signed in to $API_BASE_URL as ${login.profile.displayName}. Loading core data…"
    }

    fun tryRestoreSession(): Boolean {
        val session = BrowserSessionStore.load() ?: return false
        ApiClient.accessToken = session.accessToken
        ApiClient.refreshToken = session.refreshToken
        signedIn = true
        signedInDisplayName = session.displayName
        apiConnected = true
        notice = "Restored session · reloading core data…"
        return true
    }

    fun signOut() {
        BrowserSessionStore.clear()
        ApiClient.clearSession()
        signedIn = false
        signedInDisplayName = "Super Admin"
        apiConnected = false
        busy = false
        sites.clear()
        terminals.clear()
        people.clear()
        keys.clear()
        keySlots.clear()
        accessGrants.clear()
        keyExtras.clear()
        keyRevisions.clear()
        keySlotRevisions.clear()
        accessGrantRevisions.clear()
        recycleBin.clear()
        recycleBinEntries.clear()
        syncConflicts.clear()
        systemLogs.clear()
        equipmentLogs.clear()
        openConflictCount = 0
        notice = null
    }

    fun reloadCoreData(onDone: ((String?) -> Unit)? = null) {
        scope.launch {
            busy = true
            try {
                val timestamp = now()
                val loadedSites = ApiClient.listSites()
                val loadedTerminals = ApiClient.listTerminals()
                val loadedPeople = ApiClient.listUsers()
                val loadedKeys = ApiClient.listKeys()
                val loadedSlots = ApiClient.listKeySlots()
                val loadedGrants = ApiClient.listAccessGrants()
                val recycle = ApiClient.listRecycleBin()
                val auditEvents = ApiClient.listAuditEvents(limit = 200)
                val conflicts = ApiClient.listSyncConflicts()

                sites.clear()
                sites.addAll(loadedSites.map {
                    PortalSite(
                        id = it.id,
                        name = it.name,
                        province = it.province.orEmpty(),
                        city = it.city.orEmpty(),
                        parentSiteId = it.parentSiteId,
                        address = it.address,
                        revision = it.revision,
                    )
                })
                terminals.clear()
                terminals.addAll(loadedTerminals.map {
                    PortalTerminal(
                        id = it.id,
                        siteId = it.siteId,
                        name = it.name,
                        deviceUid = it.serialNumber.orEmpty(),
                        boxAddress = it.boxAddress,
                        nodeLayout = NodeLayoutType.A_STANDARD_KEY_SLOT,
                        nodeCount = it.configuredSlotCount,
                        keyReturnAuthentication = false,
                        status = when (it.connectionState.name) {
                            "ONLINE" -> PortalTerminalStatus.ONLINE
                            "OFFLINE" -> PortalTerminalStatus.OFFLINE
                            else -> PortalTerminalStatus.SYNC_PENDING
                        },
                        lastSyncLabel = it.connectionState.name.lowercase().replace('_', ' '),
                        revision = it.revision,
                    )
                })
                people.clear()
                people.addAll(loadedPeople.map { user ->
                    val credentialSummary = try {
                        val statuses = ApiClient.listUserCredentials(user.id)
                        if (statuses.isEmpty()) {
                            "No enrollment requested"
                        } else {
                            statuses.joinToString { "${it.credentialKind.name}: ${it.enrollmentStatus}" }
                        }
                    } catch (_: Throwable) {
                        "Managed by terminal"
                    }
                    PortalPerson(
                        id = user.id,
                        displayName = user.displayName,
                        employeeId = user.email.substringBefore('@'),
                        siteId = user.assignedSiteIds.firstOrNull().orEmpty(),
                        userGroup = user.role.name,
                        accessWindow = "Not configured",
                        accountStatus = when (user.accountStatus.name) {
                            "ACTIVE" -> PortalAccountStatus.ACTIVE
                            "DISABLED" -> PortalAccountStatus.DISABLED
                            else -> PortalAccountStatus.PENDING_APPROVAL
                        },
                        credentialSummary = credentialSummary,
                        email = user.email,
                        revision = user.revision,
                        role = user.role,
                    )
                })
                keys.clear()
                keyRevisions.clear()
                keys.addAll(loadedKeys.map {
                    keyRevisions[it.id] = it.revision
                    ManagedKey(
                        id = it.id,
                        siteId = it.siteId,
                        displayName = it.displayName,
                        fobEnrollmentReference = it.fobEnrollmentReference,
                        lifecycle = activeLifecycle(timestamp),
                    )
                })
                keySlots.clear()
                keySlotRevisions.clear()
                keySlots.addAll(loadedSlots.map {
                    keySlotRevisions[it.id] = it.revision
                    KeySlot(
                        id = it.id,
                        terminalId = it.terminalId,
                        nodeAddress = it.nodeAddress,
                        managedKeyId = it.managedKeyId,
                        lifecycle = activeLifecycle(timestamp),
                    )
                })
                accessGrants.clear()
                accessGrantRevisions.clear()
                accessGrants.addAll(loadedGrants.map {
                    accessGrantRevisions[it.id] = it.revision
                    AccessGrant(
                        id = it.id,
                        userId = it.userId,
                        siteId = it.siteId,
                        keyIds = it.keyIds,
                        validFromEpochMillis = it.validFromEpochMillis,
                        validUntilEpochMillis = it.validUntilEpochMillis,
                        lifecycle = activeLifecycle(timestamp),
                    )
                })
                recycleBinEntries.clear()
                recycleBinEntries.addAll(recycle.entries)
                recycleBin.clear()
                syncConflicts.clear()
                syncConflicts.addAll(conflicts)
                openConflictCount = conflicts.size
                applyAuditLogs(auditEvents)
                selectedTerminalId = terminals.firstOrNull()?.id ?: selectedTerminalId
                apiConnected = true
                notice = "Connected to $API_BASE_URL. Core data loaded."
                onDone?.invoke(null)
            } catch (error: Throwable) {
                apiConnected = false
                val message = error.message ?: "Unable to load core data."
                notice = "API load failed: $message"
                onDone?.invoke(message)
            } finally {
                busy = false
            }
        }
    }

    private fun applyAuditLogs(events: List<com.ekms.shared.domain.AuditEvent>) {
        systemLogs.clear()
        equipmentLogs.clear()
        events.forEach { event ->
            val log = PortalLog(
                id = event.id,
                occurredAt = formatEpoch(event.occurredAtEpochMillis),
                actorOrTerminal = event.actorUserId ?: event.terminalId ?: "system",
                action = buildString {
                    append(event.eventType.name)
                    if (!event.detail.isNullOrBlank()) append(" · ${event.detail}")
                },
                scope = event.siteId ?: event.entityId ?: event.entityType?.name ?: "—",
            )
            when (event.eventType.name) {
                "KEY_TAKEN", "KEY_RETURNED" -> equipmentLogs.add(log)
                else -> systemLogs.add(log)
            }
        }
    }

    fun create(kind: WebDialogKind, values: List<String>) {
        var closeDialog = true
        when (kind) {
            WebDialogKind.ADD_UNIT -> {
                val name = field(values, 0, "New Unit")
                val province = values.getOrNull(1)?.trim().orEmpty().ifBlank { null }
                val city = values.getOrNull(2)?.trim().orEmpty().ifBlank { null }
                val superiorName = values.getOrNull(3)?.trim().orEmpty()
                val parentSiteId = if (superiorName.isBlank()) {
                    null
                } else {
                    optionalSiteIdFor(superiorName).also { resolved ->
                        if (resolved == null) {
                            notice = "Superior unit \"$superiorName\" was not found. Enter an existing unit name, or leave blank."
                            closeDialog = false
                        }
                    }
                }
                if (closeDialog) {
                    createWithApi("Creating $name…") {
                        ApiClient.createSite(
                            SiteUpsertRequest(
                                name = name,
                                province = province,
                                city = city,
                                parentSiteId = parentSiteId,
                            ),
                        )
                    }
                }
            }

            WebDialogKind.ADD_TERMINAL -> {
                val siteId = siteIdFor(field(values, 1, ""))
                if (siteId.isBlank()) {
                    notice = "Select a valid unit before saving a terminal."
                    closeDialog = false
                } else {
                    createWithApi("Creating ${field(values, 0, "New Cabinet")}…") {
                        ApiClient.createTerminal(
                            TerminalUpsertRequest(
                                siteId = siteId,
                                name = field(values, 0, "New Cabinet"),
                                boxAddress = 1,
                                serialNumber = field(values, 2, "Device UID pending"),
                                configuredSlotCount = field(values, 4, "24").toIntOrNull()?.coerceIn(1, 255) ?: 24,
                            ),
                        )
                    }
                }
            }

            WebDialogKind.ADD_PERSON -> {
                val siteId = siteIdFor(field(values, 1, ""))
                if (siteId.isBlank()) {
                    notice = "Assign a unit before creating personnel."
                    closeDialog = false
                } else {
                    val name = field(values, 0, "New person")
                    val employeeId = field(values, 2, "")
                    val email = employeeId.takeIf { it.contains("@") } ?: "${name.lowercase().replace(" ", ".")}@ekms.local"
                    val password = values.getOrNull(5)?.trim()?.takeIf { it.length >= 8 }
                    createWithApi("Creating $name…") {
                        ApiClient.createUser(
                            displayName = name,
                            email = email,
                            role = UserRole.TECHNICIAN,
                            assignedSiteIds = setOf(siteId),
                            password = password,
                        )
                    }
                }
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
                        createWithApi("Creating ${displayName.trim()}…") {
                            val key = ApiClient.createKey(
                                KeyUpsertRequest(siteId = terminal.siteId, displayName = displayName.trim()),
                            )
                            ApiClient.createKeySlot(
                                KeySlotUpsertRequest(
                                    terminalId = terminal.id,
                                    nodeAddress = nodeAddressText.trim().toInt(),
                                    managedKeyId = key.id,
                                ),
                            )
                        }
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
        if (apiConnected) {
            createWithApi("Saving key permission…") {
                ApiClient.createAccessGrant(
                    AccessGrantUpsertRequest(
                        userId = personId,
                        siteId = key.siteId,
                        keyIds = setOf(keyId),
                    ),
                )
            }
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
        if (!apiConnected) {
            notice = "Sign in before moving access grants to the Recycle Bin."
            return
        }
        mutateWithApi("Moving access grant to Recycle Bin…") {
            ApiClient.deleteAccessGrant(grant.id)
        }
    }

    fun restoreAccessGrant(grant: AccessGrant) {
        restoreRecycleEntry(RecordType.ACCESS_GRANT, grant.id)
    }

    fun purgeAccessGrant(grant: AccessGrant) {
        purgeRecycleEntry(RecordType.ACCESS_GRANT, grant.id)
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
        if (!apiConnected) {
            notice = "Sign in before moving units to the Recycle Bin."
            return
        }
        mutateWithApi("Moving ${site.name} to Recycle Bin…") {
            ApiClient.deleteSite(site.id)
        }
    }

    fun archiveTerminal(terminal: PortalTerminal) {
        if (keySlots.any { it.terminalId == terminal.id && it.lifecycle.state == RecordLifecycle.ACTIVE }) {
            notice = "Archive or move the terminal's assigned keys first. Cabinet dependencies cannot be silently deleted."
            return
        }
        if (!apiConnected) {
            notice = "Sign in before moving terminals to the Recycle Bin."
            return
        }
        mutateWithApi("Moving ${terminal.name} to Recycle Bin…") {
            ApiClient.deleteTerminal(terminal.id)
        }
    }

    fun archivePerson(person: PortalPerson) {
        if (!apiConnected) {
            notice = "Sign in before moving personnel to the Recycle Bin."
            return
        }
        mutateWithApi("Moving ${person.displayName} to Recycle Bin…") {
            ApiClient.deleteUser(person.id)
        }
    }

    fun disablePerson(person: PortalPerson) {
        if (!apiConnected) {
            notice = "Sign in before changing account status."
            return
        }
        mutateWithApi("Disabling ${person.displayName}…") {
            ApiClient.updateUserAccountStatus(person.id, AccountStatus.DISABLED, person.revision)
        }
    }

    fun enablePerson(person: PortalPerson) {
        if (!apiConnected) {
            notice = "Sign in before changing account status."
            return
        }
        mutateWithApi("Enabling ${person.displayName}…") {
            ApiClient.updateUserAccountStatus(person.id, AccountStatus.ACTIVE, person.revision)
        }
    }

    fun requestCredentialEnrollment(person: PortalPerson, kind: CredentialKind = CredentialKind.NFC_CARD) {
        if (!apiConnected) {
            notice = "Sign in before requesting credential enrollment."
            return
        }
        val terminalId = terminals.firstOrNull { it.siteId == person.siteId }?.id
            ?: terminals.firstOrNull()?.id
        mutateWithApi("Requesting ${kind.name} enrollment for ${person.displayName}…") {
            ApiClient.requestCredentialEnrollment(
                userId = person.id,
                credentialKind = kind,
                terminalId = terminalId,
                note = "Requested from Website",
            )
        }
    }

    fun archiveKey(key: ManagedKey) {
        val hasActiveGrant = accessGrants.any {
            it.lifecycle.state == RecordLifecycle.ACTIVE && key.id in it.keyIds
        }
        if (hasActiveGrant) {
            notice = "Remove ${key.displayName} from its active access grants before moving it to the Recycle Bin."
            return
        }
        if (!apiConnected) {
            notice = "Sign in before moving keys to the Recycle Bin."
            return
        }
        mutateWithApi("Moving ${key.displayName} to Recycle Bin…") {
            ApiClient.deleteKey(key.id)
        }
    }

    fun restoreKey(key: ManagedKey) {
        restoreRecycleEntry(RecordType.KEY, key.id)
    }

    fun purgeKey(key: ManagedKey) {
        purgeRecycleEntry(RecordType.KEY, key.id)
    }

    fun restore(record: PortalDeletedRecord) {
        when (record) {
            is PortalDeletedRecord.Site -> restoreRecycleEntry(RecordType.SITE, record.site.id)
            is PortalDeletedRecord.Terminal -> restoreRecycleEntry(RecordType.TERMINAL, record.terminal.id)
            is PortalDeletedRecord.Person -> restoreRecycleEntry(RecordType.USER, record.person.id)
        }
    }

    fun purge(record: PortalDeletedRecord) {
        when (record) {
            is PortalDeletedRecord.Site -> purgeRecycleEntry(RecordType.SITE, record.site.id)
            is PortalDeletedRecord.Terminal -> purgeRecycleEntry(RecordType.TERMINAL, record.terminal.id)
            is PortalDeletedRecord.Person -> purgeRecycleEntry(RecordType.USER, record.person.id)
        }
    }

    fun restoreRecycleEntry(entry: RecycleBinEntry) {
        restoreRecycleEntry(entry.recordType, entry.recordId, entry.restorePayloadVersion)
    }

    fun purgeRecycleEntry(entry: RecycleBinEntry) {
        purgeRecycleEntry(entry.recordType, entry.recordId)
    }

    fun purgeExpiredRecycleBin() {
        if (!apiConnected) {
            notice = "Sign in before purging expired Recycle Bin records."
            return
        }
        mutateWithApi("Purging expired Recycle Bin records…") {
            ApiClient.purgeExpiredRecycleBin()
            "Expired Recycle Bin records purged."
        }
    }

    fun readFromTerminal(terminal: PortalTerminal) {
        if (!apiConnected) {
            notice = "Sign in before requesting terminal sync."
            return
        }
        mutateWithApi("Requesting read from ${terminal.name}…") {
            val ack = ApiClient.terminalSyncRead(terminal.id)
            ack.message ?: "Read request accepted for ${terminal.name}."
        }
    }

    fun downloadToTerminal(terminal: PortalTerminal) {
        if (!apiConnected) {
            notice = "Sign in before downloading configuration."
            return
        }
        mutateWithApi("Downloading configuration to ${terminal.name}…") {
            val ack = ApiClient.terminalSyncDownload(terminal.id)
            ack.message ?: "Download staged for ${terminal.name} (revision ${ack.serverRevision})."
        }
    }

    fun resolveConflict(strategy: ConflictResolutionStrategy) {
        val conflict = syncConflicts.firstOrNull()
        if (conflict == null) {
            notice = "No open sync conflicts to resolve."
            return
        }
        if (!apiConnected) {
            notice = "Sign in before resolving conflicts."
            return
        }
        mutateWithApi("Resolving conflict with $strategy…") {
            ApiClient.resolveSyncConflict(conflict.id, strategy)
            "Conflict resolved with $strategy."
        }
    }

    private fun restoreRecycleEntry(recordType: RecordType, recordId: String, expectedRevision: Long? = null) {
        if (!apiConnected) {
            notice = "Sign in before restoring Recycle Bin records."
            return
        }
        mutateWithApi("Restoring $recordType…") {
            ApiClient.restoreRecycleBinEntry(recordType, recordId, expectedRevision)
            "Restored $recordType."
        }
    }

    private fun purgeRecycleEntry(recordType: RecordType, recordId: String) {
        if (!apiConnected) {
            notice = "Sign in before purging Recycle Bin records."
            return
        }
        mutateWithApi("Purging $recordType permanently…") {
            ApiClient.purgeRecycleBinEntry(recordType, recordId)
            "Purged $recordType permanently. Audit history is retained."
        }
    }

    fun siteName(siteId: String): String = sites.firstOrNull { it.id == siteId }?.name ?: "Unassigned unit"

    fun parentUnitName(site: PortalSite): String =
        site.parentSiteId
            ?.let { parentId -> sites.firstOrNull { it.id == parentId }?.name }
            .orEmpty()

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

    private fun optionalSiteIdFor(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        return sites.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.id
    }

    private fun siteIdFor(name: String): String =
        sites.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.id ?: sites.firstOrNull()?.id.orEmpty()

    private fun createWithApi(action: String, request: suspend () -> Any) {
        if (!apiConnected) {
            notice = "Sign in to $API_BASE_URL before saving core records."
            return
        }
        mutateWithApi(action) {
            request()
            "Saved successfully."
        }
    }

    private fun mutateWithApi(action: String, request: suspend () -> Any?) {
        scope.launch {
            busy = true
            notice = action
            try {
                val result = request()
                val successNotice = result as? String ?: "Updated successfully."
                reloadCoreData { error ->
                    if (error == null) notice = successNotice
                }
            } catch (error: Throwable) {
                notice = "Request failed: ${error.message ?: "Unknown API error"}"
                busy = false
            }
        }
    }

    private fun nextId(prefix: String): String = "$prefix-${nextLocalId++}"

    private fun formatEpoch(epochMs: Long): String {
        val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC)
        return "${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')} UTC"
    }

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
        fields = listOf(
            "Unit name",
            "Province / state",
            "City",
            "Superior unit (existing unit name, optional)",
        ),
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
    val parentSiteId: String? = null,
    val address: String? = null,
    val revision: Long = 1,
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
    val revision: Long = 1,
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
    val email: String = "",
    val revision: Long = 1,
    val role: UserRole = UserRole.TECHNICIAN,
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
