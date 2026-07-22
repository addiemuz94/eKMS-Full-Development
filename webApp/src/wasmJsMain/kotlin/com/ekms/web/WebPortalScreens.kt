package com.ekms.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ekms.shared.api.ApiPaths
import com.ekms.shared.domain.AccessGrant
import com.ekms.shared.domain.ManagedKey
import com.ekms.shared.domain.RecordLifecycle
import com.ekms.shared.policy.RecycleBinPolicy
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Supplier-workflow Web portal. All records are local preview data until the API is connected. */
@Composable
internal fun EkmsWebApp() {
    val store = remember { WebPortalStore() }

    if (store.signedIn) {
        WebPortal(store = store)
    } else {
        WebLoginScreen(
            onSignIn = {
                store.signedIn = true
                store.notice = "Local workflow preview opened. Production sign-in must use ${ApiPaths.AUTH_LOGIN} and a deployment-provisioned Super Admin account."
            },
        )
    }
}

@Composable
private fun WebLoginScreen(onSignIn: () -> Unit) {
    var company by remember { mutableStateOf("Cavotec Malaysia") }
    var account by remember { mutableStateOf("Super Admin") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 500.dp)
                .padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("eK", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "Electronic Key Management System",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Supplier-aligned Web management portal",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Enter the company, account and password to sign in. The Web client never stores the bootstrap password; production authentication is handled by the backend.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = company,
                    onValueChange = { company = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Company / organisation") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Account") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null,
                    supportingText = {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                )
                Button(
                    onClick = {
                        if (company.isBlank() || account.isBlank() || password.isBlank()) {
                            error = "Enter company, account and password to continue."
                        } else {
                            error = null
                            onSignIn()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign in")
                }
                Text(
                    "Only the deployment-created Super Admin is preset. New personnel and keys are created from the management portal after sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WebPortal(store: WebPortalStore) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val compact = maxWidth < 920.dp
        Column(modifier = Modifier.fillMaxSize()) {
            PortalTopBar(
                currentRoute = store.route,
                onSignOut = {
                    store.signedIn = false
                    store.route = WebRoute.DASHBOARD
                    store.notice = null
                },
            )
            if (compact) {
                CompactRoutePicker(
                    selected = store.route,
                    onRouteSelected = { store.route = it },
                )
                HorizontalDivider()
                PortalContent(
                    store = store,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    PortalSidebar(
                        selected = store.route,
                        onRouteSelected = { store.route = it },
                        modifier = Modifier
                            .width(258.dp)
                            .fillMaxHeight(),
                    )
                    Spacer(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
                    )
                    PortalContent(
                        store = store,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }

    store.dialogKind?.let { kind ->
        PortalEntryDialog(
            kind = kind,
            onDismiss = { store.dialogKind = null },
            onSubmit = { values -> store.create(kind, values) },
        )
    }
}

@Composable
private fun PortalTopBar(currentRoute: WebRoute, onSignOut: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                color = Color.White.copy(alpha = 0.18f),
                shape = MaterialTheme.shapes.small,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("eK", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Electronic Key Management System", fontWeight = FontWeight.Bold)
                Text(currentRoute.label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("Super Admin", style = MaterialTheme.typography.labelLarge)
                Text("Website management portal", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.78f))
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onSignOut) {
                Text("Sign out", color = Color.White)
            }
        }
    }
}

@Composable
private fun PortalSidebar(
    selected: WebRoute,
    onRouteSelected: (WebRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WebNavigationGroups.forEach { group ->
            Text(
                group.title,
                modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            group.routes.forEach { route ->
                if (route == selected) {
                    Button(
                        onClick = { onRouteSelected(route) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(route.label, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    TextButton(
                        onClick = { onRouteSelected(route) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            route.label,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactRoutePicker(selected: WebRoute, onRouteSelected: (WebRoute) -> Unit) {
    val options = WebRoute.entries.map { SelectionOption(it.name, it.label) }
    PortalSelectField(
        label = "Website section",
        selectedId = selected.name,
        options = options,
        onSelected = { value -> WebRoute.entries.firstOrNull { it.name == value }?.let(onRouteSelected) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun PortalContent(store: WebPortalStore, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        store.notice?.let { message ->
            NoticeBanner(message = message, onDismiss = { store.notice = null })
        }
        when (store.route) {
            WebRoute.DASHBOARD -> DashboardScreen(store)
            WebRoute.UNITS -> UnitsScreen(store)
            WebRoute.TERMINALS -> TerminalsScreen(store)
            WebRoute.PERSONNEL -> PersonnelScreen(store)
            WebRoute.KEYS -> KeysScreen(store)
            WebRoute.PERMISSIONS -> PermissionsScreen(store)
            WebRoute.EVENTS -> EventsScreen(store)
            WebRoute.SCHEDULES -> SchedulesScreen(store)
            WebRoute.MULTI_AUTH_RULES -> MultiAuthRulesScreen(store)
            WebRoute.USER_GROUPS -> GroupsScreen(store, userGroups = true)
            WebRoute.KEY_GROUPS -> GroupsScreen(store, userGroups = false)
            WebRoute.DATA_SYNC -> DataSynchronizationScreen(store)
            WebRoute.KEY_RECORDS -> KeyRecordsScreen(store)
            WebRoute.OPERATION_LOGS -> OperationLogScreen(store)
            WebRoute.APPOINTMENTS -> AppointmentsScreen(store)
            WebRoute.APPOINTMENT_REASONS -> AppointmentReasonsScreen(store)
            WebRoute.APPOINTMENT_PERMISSIONS -> AppointmentPermissionsScreen(store)
            WebRoute.SYSTEM_LOGS -> SystemLogsScreen(store)
            WebRoute.EQUIPMENT_LOGS -> EquipmentLogsScreen(store)
            WebRoute.RECYCLE_BIN -> RecycleBinScreen(store)
        }
    }
}

@Composable
private fun DashboardScreen(store: WebPortalStore) {
    val activeKeys = store.keys.count { it.lifecycle.state == RecordLifecycle.ACTIVE }
    val assignedSlots = store.keySlots.count { it.lifecycle.state == RecordLifecycle.ACTIVE && it.managedKeyId != null }
    val onlineTerminals = store.terminals.count { it.status == PortalTerminalStatus.ONLINE }

    PageHeader(
        title = "Home",
        description = "View cabinet-key status and real-time information across the managed organisation.",
    )
    MetricGrid(
        listOf(
            DashboardMetric("Active keys", activeKeys.toString(), "Registered logical key records"),
            DashboardMetric("Assigned cabinet slots", assignedSlots.toString(), "Keys mapped to a Box/Node address"),
            DashboardMetric("Online terminals", "${onlineTerminals}/${store.terminals.size}", "Terminal status from backend"),
            DashboardMetric("Pending approvals", store.appointments.count { it.status == AppointmentStatus.PENDING }.toString(), "Appointments needing review"),
        ),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Cabinet overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("The Website shows status, records and requested synchronisation. Physical door/key-peg actions stay exclusively on an authorised Android Terminal.")
            store.terminals.forEach { terminal ->
                HorizontalDivider()
                Text(terminal.name, fontWeight = FontWeight.SemiBold)
                Text("${store.siteName(terminal.siteId)} · ${terminal.nodeLayout.label} · ${terminal.nodeCount} nodes")
                StatusLabel("${terminal.status.label} · ${terminal.lastSyncLabel}")
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Quick actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = { store.route = WebRoute.APPOINTMENTS }) { Text("Review appointment authorization") }
            OutlinedButton(onClick = { store.route = WebRoute.DATA_SYNC }) { Text("Open data synchronization") }
            OutlinedButton(onClick = { store.route = WebRoute.KEY_RECORDS }) { Text("View pickup & return records") }
        }
    }
}

@Composable
private fun UnitsScreen(store: WebPortalStore) {
    var query by remember { mutableStateOf("") }
    val records = store.sites.filter {
        it.name.matchesQuery(query) || it.province.matchesQuery(query) || it.city.matchesQuery(query) || it.parentUnit.matchesQuery(query)
    }

    PageHeader(
        title = "Unit Settings",
        description = "Create the organisation/site hierarchy used by terminals, personnel, keys, permissions and reports.",
        primaryLabel = "Add unit",
        onPrimary = { store.openDialog(WebDialogKind.ADD_UNIT) },
    )
    SearchField(value = query, onValueChange = { query = it }, label = "Search unit, province, city or superior unit")
    if (records.isEmpty()) EmptyState("No active unit matches the current search.")
    records.forEach { site ->
        RecordCard(
            title = site.name,
            status = "Active",
            lines = listOf(
                "Province / state: ${site.province}",
                "City: ${site.city}",
                "Superior unit: ${site.parentUnit}",
            ),
        ) {
            TextButton(onClick = { store.archiveSite(site) }) { Text("Move to Recycle Bin") }
        }
    }
}

@Composable
private fun TerminalsScreen(store: WebPortalStore) {
    var selectedSiteId by remember { mutableStateOf("all") }
    val siteOptions = listOf(SelectionOption("all", "All units")) + store.sites.map { SelectionOption(it.id, it.name) }
    val records = store.terminals.filter { selectedSiteId == "all" || it.siteId == selectedSiteId }

    PageHeader(
        title = "Terminal Settings",
        description = "Register Android key-cabinet terminals, match their unique device ID, configure the node layout, and define return-authentication behaviour.",
        primaryLabel = "Add Android terminal",
        onPrimary = { store.openDialog(WebDialogKind.ADD_TERMINAL) },
    )
    PortalSelectField(
        label = "Affiliated unit",
        selectedId = selectedSiteId,
        options = siteOptions,
        onSelected = { selectedSiteId = it },
    )
    if (records.isEmpty()) EmptyState("No terminal is assigned to the selected unit.")
    records.forEach { terminal ->
        RecordCard(
            title = terminal.name,
            status = terminal.status.label,
            lines = listOf(
                "Unit: ${store.siteName(terminal.siteId)}",
                "Type: Android terminal · Device ID: ${terminal.deviceUid}",
                "Node layout: ${terminal.nodeLayout.label}",
                "Configured nodes: ${terminal.nodeCount} · Return authentication: ${if (terminal.keyReturnAuthentication) "Enabled" else "Disabled"}",
                "Sync state: ${terminal.lastSyncLabel}",
            ),
        ) {
            OutlinedButton(onClick = { store.selectedTerminalId = terminal.id; store.route = WebRoute.DATA_SYNC }) {
                Text("Synchronize")
            }
            TextButton(onClick = { store.archiveTerminal(terminal) }) { Text("Move to Recycle Bin") }
        }
    }
    SafetyBoundaryCard("Saving terminal settings updates backend configuration only. The Website must never open /dev/ttyS1, /dev/ttyS2, or send cabinet frames.")
}

@Composable
private fun PersonnelScreen(store: WebPortalStore) {
    var query by remember { mutableStateOf("") }
    val records = store.people.filter {
        it.displayName.matchesQuery(query) || it.employeeId.matchesQuery(query) || store.siteName(it.siteId).matchesQuery(query)
    }

    PageHeader(
        title = "Personnel Management",
        description = "Filter and manage personnel records, site assignment, credential status, multi-authentication group, access time and exact-key permissions.",
        primaryLabel = "Add personnel",
        onPrimary = { store.openDialog(WebDialogKind.ADD_PERSON) },
    )
    SearchField(value = query, onValueChange = { query = it }, label = "Search by unit, personnel name or employee ID")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { store.notice = "PDF export is an API-backed report action in production." }) { Text("Export PDF") }
        FilledTonalButton(onClick = { store.notice = "Excel export is an API-backed report action in production." }) { Text("Export Excel") }
    }
    if (records.isEmpty()) EmptyState("No personnel record matches the current search.")
    records.forEach { person ->
        RecordCard(
            title = person.displayName,
            status = person.accountStatus.label,
            lines = listOf(
                "Employee ID: ${person.employeeId} · Unit: ${store.siteName(person.siteId)}",
                "Multi-authentication group: ${person.userGroup}",
                "Access time: ${person.accessWindow}",
                "Credential status: ${person.credentialSummary}",
            ),
        ) {
            OutlinedButton(
                onClick = {
                    store.activePersonId = person.id
                    store.route = WebRoute.PERMISSIONS
                },
            ) { Text("Permission settings") }
            TextButton(onClick = { store.archivePerson(person) }) { Text("Move to Recycle Bin") }
        }
    }
}

@Composable
private fun KeysScreen(store: WebPortalStore) {
    var query by remember { mutableStateOf("") }
    val activeKeys = store.keys.filter { it.lifecycle.state == RecordLifecycle.ACTIVE }
    val records = activeKeys.filter {
        it.displayName.matchesQuery(query) ||
                store.keyLocationLabel(it.id).matchesQuery(query) ||
                store.keyTerminalName(it.id).matchesQuery(query)
    }

    PageHeader(
        title = "Key Settings",
        description = "Create logical key records, assign each one to an exact Box Address + Node Address, and view protected fob-enrolment status without exposing any raw UID.",
        primaryLabel = "Add key",
        onPrimary = { store.openDialog(WebDialogKind.ADD_KEY) },
    )
    SearchField(value = query, onValueChange = { query = it }, label = "Search by unit, terminal, key name or node address")
    if (records.isEmpty()) EmptyState("No key record matches the current search.")
    records.forEach { key ->
        val extra = store.keyExtras[key.id]
        RecordCard(
            title = key.displayName,
            status = if (store.keySlotFor(key.id) != null) "Slotted" else "Not slotted",
            lines = listOf(
                "Unit: ${store.siteName(key.siteId)} · Terminal: ${store.keyTerminalName(key.id)}",
                "Address: ${store.keyLocationLabel(key.id)} · Time limit: ${extra?.timeLimit ?: "Not configured"}",
                "Key group: ${extra?.keyGroup ?: "Unassigned"}",
                if (key.fobEnrollmentReference.isNullOrBlank()) {
                    "Physical fob: not enrolled (status/reference only)"
                } else {
                    "Physical fob: enrolled (status/reference only)"
                },
            ),
        ) {
            OutlinedButton(onClick = { store.route = WebRoute.PERMISSIONS }) { Text("Manage permissions") }
            TextButton(onClick = { store.archiveKey(key) }) { Text("Move to Recycle Bin") }
        }
    }
    SafetyBoundaryCard("Physical fob enrolment, raw UID comparison, door eject and key-slot lock/unlock occur only in the signed-in Android Terminal workflow.")
}

@Composable
private fun PermissionsScreen(store: WebPortalStore) {
    val peopleOptions = store.people.map { SelectionOption(it.id, "${it.displayName} · ${store.siteName(it.siteId)}") }
    val activePerson = store.people.firstOrNull { it.id == store.activePersonId } ?: store.people.firstOrNull()
    if (activePerson == null) {
        PageHeader("Permission Settings", "Select an active personnel record before assigning exact keys.")
        EmptyState("Create or restore personnel first.")
        return
    }
    val grants = store.accessGrants.filter {
        it.lifecycle.state == RecordLifecycle.ACTIVE && it.userId == activePerson.id
    }
    val grantedKeyIds = grants.flatMap { it.keyIds }.toSet()
    val availableKeys = store.keys.filter {
        it.lifecycle.state == RecordLifecycle.ACTIVE && it.siteId == activePerson.siteId && it.id !in grantedKeyIds
    }

    PageHeader(
        title = "Permission Settings",
        description = "Select personnel, choose the terminal/key slot context, then bind only the exact keys they may access. A site-level assignment alone is never enough.",
    )
    PortalSelectField(
        label = "Personnel",
        selectedId = activePerson.id,
        options = peopleOptions,
        onSelected = { store.activePersonId = it },
    )
    Text("Selected unit: ${store.siteName(activePerson.siteId)}", style = MaterialTheme.typography.bodyMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Unauthorized keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (availableKeys.isEmpty()) Text("No additional active keys are available for this person.")
            availableKeys.forEach { key ->
                HorizontalDivider()
                Text(key.displayName, fontWeight = FontWeight.SemiBold)
                Text("${store.keyTerminalName(key.id)} · ${store.keyLocationLabel(key.id)}")
                OutlinedButton(onClick = { store.grantKey(activePerson.id, key.id) }) { Text("Bind exact key") }
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Authorized keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (grants.isEmpty()) Text("No exact key permission is currently assigned.")
            grants.forEach { grant ->
                grant.keyIds.forEach { keyId ->
                    HorizontalDivider()
                    Text(store.keyName(keyId), fontWeight = FontWeight.SemiBold)
                    Text("Valid until: ${grant.validUntilEpochMillis.toDisplayDate() ?: "No expiry"}")
                    TextButton(onClick = { store.archiveAccessGrant(grant) }) { Text("Move to Recycle Bin") }
                }
            }
        }
    }
}

@Composable
private fun EventsScreen(store: WebPortalStore) {
    PageHeader(
        title = "Event Setup",
        description = "Define events that may be selected or required during a key operation, including event name, number and type.",
        primaryLabel = "Add event",
        onPrimary = { store.openDialog(WebDialogKind.ADD_EVENT) },
    )
    store.eventDefinitions.forEach { event ->
        RecordCard(
            title = event.name,
            status = event.code,
            lines = listOf("Unit: ${store.siteName(event.siteId)}", "Type: ${event.type}"),
        )
    }
}

@Composable
private fun SchedulesScreen(store: WebPortalStore) {
    PageHeader(
        title = "Schedule Settings",
        description = "Create daily, weekly or monthly schedules for access windows, appointments and policy-driven key availability.",
        primaryLabel = "Add schedule",
        onPrimary = { store.openDialog(WebDialogKind.ADD_SCHEDULE) },
    )
    store.schedules.forEach { schedule ->
        RecordCard(
            title = schedule.name,
            status = schedule.frequency,
            lines = listOf("Unit: ${store.siteName(schedule.siteId)}", "Time period: ${schedule.timeWindow}"),
        )
    }
}

@Composable
private fun MultiAuthRulesScreen(store: WebPortalStore) {
    PageHeader(
        title = "Multi-authentication Rules",
        description = "Combine a primary personnel group, one or two assistant groups, and a key group to require an additional approval before a defined key group can be taken.",
        primaryLabel = "Add multi-authentication rule",
        onPrimary = { store.openDialog(WebDialogKind.ADD_MULTI_AUTH_RULE) },
    )
    MultiAuthShortcuts(store)
    if (store.multiAuthRules.isEmpty()) EmptyState("No multi-authentication rule is configured.")
    store.multiAuthRules.forEach { rule ->
        RecordCard(
            title = "${rule.primaryUserGroup} → ${rule.keyGroup}",
            status = "Active",
            lines = listOf(
                "Unit: ${store.siteName(rule.siteId)}",
                "Assistant group 1: ${rule.assistantGroupOne}",
                "Assistant group 2: ${rule.assistantGroupTwo.ifBlank { "Not required" }}",
            ),
        )
    }
}

@Composable
private fun GroupsScreen(store: WebPortalStore, userGroups: Boolean) {
    val groups = if (userGroups) store.userGroups else store.keyGroups
    val title = if (userGroups) "User Groups" else "Key Groups"
    val description = if (userGroups) {
        "Create personnel group names and group numbers for multi-authentication policy rules."
    } else {
        "Create key group names and group numbers for multi-authentication policy rules."
    }
    PageHeader(
        title = title,
        description = description,
        primaryLabel = if (userGroups) "Add user group" else "Add key group",
        onPrimary = { store.openDialog(if (userGroups) WebDialogKind.ADD_USER_GROUP else WebDialogKind.ADD_KEY_GROUP) },
    )
    MultiAuthShortcuts(store)
    groups.forEach { group ->
        RecordCard(
            title = group.name,
            status = group.number,
            lines = listOf("Unit: ${store.siteName(group.siteId)}"),
        )
    }
}

@Composable
private fun MultiAuthShortcuts(store: WebPortalStore) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { store.route = WebRoute.MULTI_AUTH_RULES }) { Text("Rules") }
        OutlinedButton(onClick = { store.route = WebRoute.USER_GROUPS }) { Text("User groups") }
        OutlinedButton(onClick = { store.route = WebRoute.KEY_GROUPS }) { Text("Key groups") }
    }
}

@Composable
private fun DataSynchronizationScreen(store: WebPortalStore) {
    val terminals = store.terminals
    val selectedTerminal = terminals.firstOrNull { it.id == store.selectedTerminalId } ?: terminals.firstOrNull()

    PageHeader(
        title = "Data Synchronization",
        description = "Select a terminal and choose Read to retrieve its local data to the backend, or Download to send approved server configuration to the terminal.",
    )
    if (selectedTerminal == null) {
        EmptyState("Create or restore a terminal before running synchronization.")
        return
    }
    PortalSelectField(
        label = "Terminal",
        selectedId = selectedTerminal.id,
        options = terminals.map { SelectionOption(it.id, "${it.name} · ${store.siteName(it.siteId)}") },
        onSelected = { store.selectedTerminalId = it },
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Select data scope", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Personnel, credentials, keys, slots, permissions, schedules, appointments and safe audit records are synchronised as revision-aware data.")
            Text("Raw fob UIDs, fingerprint templates and face templates are excluded from Web payloads.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        store.notice = "Read request staged for ${selectedTerminal.name}. Production endpoint: ${ApiPaths.TERMINAL_DATA_READ}; the backend must persist terminal changes and create conflicts when revisions differ."
                    },
                ) { Text("Read from terminal") }
                OutlinedButton(
                    onClick = {
                        store.notice = "Download request staged for ${selectedTerminal.name}. Production endpoint: ${ApiPaths.TERMINAL_DATA_DOWNLOAD}; the terminal confirms actual receipt."
                    },
                ) { Text("Download to terminal") }
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Conflict review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${store.openConflictCount} offline edit conflict${if (store.openConflictCount == 1) "" else "s"} awaiting Super Admin review.")
            if (store.openConflictCount > 0) {
                Text("Example: terminal key-slot edit conflicts with a newer Web edit. The backend preserves both values; it must never silently overwrite either record.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { store.openConflictCount = 0; store.notice = "Conflict marked Keep server in the local preview. Production uses ${ApiPaths.SYNC_CONFLICTS}/{id}/resolve with expected revision." }) {
                        Text("Keep server")
                    }
                    OutlinedButton(onClick = { store.openConflictCount = 0; store.notice = "Conflict marked Keep terminal change in the local preview." }) {
                        Text("Keep terminal change")
                    }
                    OutlinedButton(onClick = { store.openConflictCount = 0; store.notice = "Conflict marked Merge manually in the local preview." }) {
                        Text("Merge manually")
                    }
                }
            }
        }
    }
    SafetyBoundaryCard("Data Synchronization sends high-level, authenticated data requests only. It is not a remote serial-console or remote cabinet-control channel.")
}

@Composable
private fun KeyRecordsScreen(store: WebPortalStore) {
    var query by remember { mutableStateOf("") }
    val records = store.keyRecords.filter {
        it.terminalName.matchesQuery(query) || it.keyName.matchesQuery(query) || it.personName.matchesQuery(query) || it.status.matchesQuery(query)
    }
    PageHeader(
        title = "Pickup & Return Records",
        description = "Query records by date, terminal, key and personnel. Physical status is recorded only after the Terminal reports the configured proof of take/return.",
    )
    SearchField(value = query, onValueChange = { query = it }, label = "Filter by terminal, key, personnel or status")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { store.notice = "Report PDF export requires an authenticated backend-generated file." }) { Text("Export PDF") }
        FilledTonalButton(onClick = { store.notice = "Report Excel export requires an authenticated backend-generated file." }) { Text("Export Excel") }
    }
    if (records.isEmpty()) EmptyState("No pickup or return record matches the current filter.")
    records.forEach { record ->
        RecordCard(
            title = "${record.status} · ${record.keyName}",
            status = record.occurredAt,
            lines = listOf(
                "Terminal: ${record.terminalName} · Personnel: ${record.personName}",
                record.detail,
            ),
        )
    }
}

@Composable
private fun OperationLogScreen(store: WebPortalStore) {
    PageHeader(
        title = "Operation Log",
        description = "Query system and terminal operation history using the selected conditions. Audit events are append-only in production.",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { store.route = WebRoute.SYSTEM_LOGS }) { Text("System operation log") }
        OutlinedButton(onClick = { store.route = WebRoute.EQUIPMENT_LOGS }) { Text("Equipment operation log") }
    }
    store.systemLogs.take(3).forEach { log ->
        RecordCard(log.action, log.occurredAt, listOf("Actor: ${log.actorOrTerminal}", "Scope: ${log.scope}"))
    }
}

@Composable
private fun AppointmentsScreen(store: WebPortalStore) {
    PageHeader(
        title = "Appointment Authorization",
        description = "Create a requested key pickup with unit, terminal, exact key, person, date/time and reason. A reviewer approves or rejects it before any temporary permission exists.",
        primaryLabel = "Add appointment",
        onPrimary = { store.openDialog(WebDialogKind.ADD_APPOINTMENT) },
    )
    AppointmentShortcuts(store)
    if (store.appointments.isEmpty()) EmptyState("No appointment authorization is awaiting review.")
    store.appointments.forEach { appointment ->
        RecordCard(
            title = store.personName(appointment.personId),
            status = appointment.status.label,
            lines = listOf(
                "Unit: ${store.siteName(appointment.siteId)} · Terminal: ${store.terminalName(appointment.terminalId)}",
                "Keys: ${appointment.keyIds.joinToString { store.keyName(it) }}",
                "Pickup window: ${appointment.pickupWindow}",
                "Reason: ${appointment.reason}",
            ),
        ) {
            if (appointment.status == AppointmentStatus.PENDING) {
                Button(onClick = { store.reviewAppointment(appointment.id, AppointmentStatus.APPROVED) }) { Text("Approve") }
                OutlinedButton(onClick = { store.reviewAppointment(appointment.id, AppointmentStatus.REJECTED) }) { Text("Reject") }
            }
            OutlinedButton(
                onClick = {
                    store.activeAppointmentId = appointment.id
                    store.route = WebRoute.APPOINTMENT_PERMISSIONS
                },
            ) { Text("Permission settings") }
        }
    }
}

@Composable
private fun AppointmentReasonsScreen(store: WebPortalStore) {
    PageHeader(
        title = "Appointment Reason Settings",
        description = "Maintain the approved reason list shown when an appointment authorization is created.",
        primaryLabel = "Add appointment reason",
        onPrimary = { store.openDialog(WebDialogKind.ADD_APPOINTMENT_REASON) },
    )
    AppointmentShortcuts(store)
    store.appointmentReasons.forEach { reason ->
        RecordCard(reason.name, "Available", listOf("Unit: ${store.siteName(reason.siteId)}"))
    }
}

@Composable
private fun AppointmentPermissionsScreen(store: WebPortalStore) {
    val appointment = store.appointments.firstOrNull { it.id == store.activeAppointmentId } ?: store.appointments.firstOrNull()
    if (appointment == null) {
        PageHeader("Appointment Permission Settings", "Select an appointment before binding exact key permissions.")
        EmptyState("No appointment is available.")
        return
    }
    val candidates = store.keys.filter {
        it.lifecycle.state == RecordLifecycle.ACTIVE && it.siteId == appointment.siteId && it.id !in appointment.keyIds
    }

    PageHeader(
        title = "Appointment Permission Settings",
        description = "Review the selected appointment and bind/remove exact keys. Approval remains time-bounded and must be enforced by the backend and Terminal cache.",
    )
    AppointmentShortcuts(store)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("${store.personName(appointment.personId)} · ${appointment.status.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Terminal: ${store.terminalName(appointment.terminalId)}")
            Text("Window: ${appointment.pickupWindow}")
            Text("Authorized keys", fontWeight = FontWeight.SemiBold)
            appointment.keyIds.forEach { keyId -> Text("• ${store.keyName(keyId)}") }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Unauthorized keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (candidates.isEmpty()) Text("No additional key is available in this appointment's unit.")
            candidates.forEach { key ->
                HorizontalDivider()
                Text(key.displayName, fontWeight = FontWeight.SemiBold)
                Text("${store.keyTerminalName(key.id)} · ${store.keyLocationLabel(key.id)}")
                OutlinedButton(onClick = { store.addAppointmentKey(appointment.id, key.id) }) { Text("Bind exact key") }
            }
        }
    }
}

@Composable
private fun AppointmentShortcuts(store: WebPortalStore) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { store.route = WebRoute.APPOINTMENTS }) { Text("Authorization") }
        OutlinedButton(onClick = { store.route = WebRoute.APPOINTMENT_REASONS }) { Text("Reasons") }
        OutlinedButton(onClick = { store.route = WebRoute.APPOINTMENT_PERMISSIONS }) { Text("Permissions") }
    }
}

@Composable
private fun SystemLogsScreen(store: WebPortalStore) {
    PageHeader(
        title = "System Operation Log",
        description = "Query administrative and security operations by unit, actor and time. Existing audit events remain even when a record is purged.",
    )
    store.systemLogs.forEach { log ->
        RecordCard(log.action, log.occurredAt, listOf("Actor: ${log.actorOrTerminal}", "Scope: ${log.scope}"))
    }
}

@Composable
private fun EquipmentLogsScreen(store: WebPortalStore) {
    PageHeader(
        title = "Equipment Operation Log",
        description = "Query daily terminal/cabinet operation reports. The backend stores safe hardware outcomes, not raw serial command frames.",
    )
    store.equipmentLogs.forEach { log ->
        RecordCard(log.action, log.occurredAt, listOf("Terminal: ${log.actorOrTerminal}", "Node / scope: ${log.scope}"))
    }
}

@Composable
private fun RecycleBinScreen(store: WebPortalStore) {
    val deletedKeys = store.keys.filter { it.lifecycle.state == RecordLifecycle.RECYCLE_BIN }
    val deletedGrants = store.accessGrants.filter { it.lifecycle.state == RecordLifecycle.RECYCLE_BIN }
    val isEmpty = store.recycleBin.isEmpty() && deletedKeys.isEmpty() && deletedGrants.isEmpty()

    PageHeader(
        title = "Recycle Bin",
        description = "Super Admin only. Archived Units, Terminals, Personnel, Keys and Access Grants may be restored for ${RecycleBinPolicy.RETENTION_DAYS} days or permanently cleared earlier. Immutable audit history remains retained.",
    )
    if (isEmpty) EmptyState("No record is in the Recycle Bin.")
    store.recycleBin.forEach { record ->
        RecordCard(
            title = record.label,
            status = record.typeLabel,
            lines = listOf("Restorable for $RECYCLE_RETENTION_LABEL from deletion in the production backend."),
        ) {
            OutlinedButton(onClick = { store.restore(record) }) { Text("Restore") }
            TextButton(onClick = { store.purge(record) }) { Text("Clear permanently") }
        }
    }
    deletedKeys.forEach { key ->
        RecordCard(
            title = key.displayName,
            status = "Key",
            lines = listOf("Restorable for $RECYCLE_RETENTION_LABEL from deletion in the production backend."),
        ) {
            OutlinedButton(onClick = { store.restoreKey(key) }) { Text("Restore") }
            TextButton(onClick = { store.purgeKey(key) }) { Text("Clear permanently") }
        }
    }
    deletedGrants.forEach { grant ->
        RecordCard(
            title = "${store.personName(grant.userId)} · ${grant.keyIds.joinToString { store.keyName(it) }}",
            status = "Access grant",
            lines = listOf("Restorable for $RECYCLE_RETENTION_LABEL from deletion in the production backend."),
        ) {
            OutlinedButton(onClick = { store.restoreAccessGrant(grant) }) { Text("Restore") }
            TextButton(onClick = { store.purgeAccessGrant(grant) }) { Text("Clear permanently") }
        }
    }
}

@Composable
private fun PageHeader(
    title: String,
    description: String,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (primaryLabel != null && onPrimary != null) {
            Button(onClick = onPrimary) { Text(primaryLabel) }
        }
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun MetricGrid(metrics: List<DashboardMetric>) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 620.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                metrics.forEach { MetricCard(it, Modifier.fillMaxWidth()) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                metrics.chunked(2).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { metric -> MetricCard(metric, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(metric: DashboardMetric, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(metric.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(metric.value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(metric.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
    )
}

@Composable
private fun PortalSelectField(
    label: String,
    selectedId: String,
    options: List<SelectionOption>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(label, selectedId, options.map { it.id }) { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }?.label ?: "Select"
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("⌄")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            expanded = false
                            onSelected(option.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordCard(
    title: String,
    status: String? = null,
    lines: List<String>,
    actions: @Composable () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                status?.let { StatusLabel(it) }
            }
            lines.forEach { line -> Text(line, style = MaterialTheme.typography.bodyMedium) }
            actions()
        }
    }
}

@Composable
private fun StatusLabel(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyState(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(message, modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SafetyBoundaryCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Terminal-only hardware boundary", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(message, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun PortalEntryDialog(
    kind: WebDialogKind,
    onDismiss: () -> Unit,
    onSubmit: (List<String>) -> Unit,
) {
    var values by remember(kind) { mutableStateOf(List(kind.fields.size) { "" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                kind.fields.forEachIndexed { index, label ->
                    OutlinedTextField(
                        value = values[index],
                        onValueChange = { value ->
                            values = values.toMutableList().also { it[index] = value }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(label) },
                        singleLine = index != kind.fields.lastIndex,
                        visualTransformation = if (label.contains("password", ignoreCase = true)) {
                            PasswordVisualTransformation()
                        } else {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        },
                    )
                }
                Text(
                    "This screen validates the supplier workflow locally. Production submit is revision-safe and audited through the eKMS backend API.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = { onSubmit(values) }) { Text(kind.submitLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private data class WebNavigationGroup(val title: String, val routes: List<WebRoute>)

private val WebNavigationGroups = listOf(
    WebNavigationGroup("Home", listOf(WebRoute.DASHBOARD)),
    WebNavigationGroup(
        "Basic Settings",
        listOf(
            WebRoute.UNITS,
            WebRoute.TERMINALS,
            WebRoute.PERSONNEL,
            WebRoute.KEYS,
            WebRoute.PERMISSIONS,
            WebRoute.EVENTS,
            WebRoute.SCHEDULES,
            WebRoute.MULTI_AUTH_RULES,
            WebRoute.USER_GROUPS,
            WebRoute.KEY_GROUPS,
        ),
    ),
    WebNavigationGroup("Data Synchronization", listOf(WebRoute.DATA_SYNC)),
    WebNavigationGroup("Report Data", listOf(WebRoute.KEY_RECORDS, WebRoute.OPERATION_LOGS)),
    WebNavigationGroup(
        "Appointment Authorization",
        listOf(WebRoute.APPOINTMENTS, WebRoute.APPOINTMENT_REASONS, WebRoute.APPOINTMENT_PERMISSIONS),
    ),
    WebNavigationGroup("Logs", listOf(WebRoute.SYSTEM_LOGS, WebRoute.EQUIPMENT_LOGS)),
    WebNavigationGroup("Super Admin", listOf(WebRoute.RECYCLE_BIN)),
)

private data class DashboardMetric(val label: String, val value: String, val detail: String)

private data class SelectionOption(val id: String, val label: String)

private fun String.matchesQuery(query: String): Boolean = query.isBlank() || contains(query.trim(), ignoreCase = true)

private fun Long?.toDisplayDate(): String? = this?.let {
    Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString()
}