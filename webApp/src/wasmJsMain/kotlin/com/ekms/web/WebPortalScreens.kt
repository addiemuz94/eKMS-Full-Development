package com.ekms.web

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.AccessGrant
import com.ekms.shared.domain.ManagedKey
import com.ekms.shared.domain.RecordLifecycle
import com.ekms.shared.policy.RecycleBinPolicy
import com.ekms.shared.sync.ConflictResolutionStrategy
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Supplier-workflow Web portal. Core admin data loads from the MySQL-backed API after sign-in. */
@Composable
internal fun EkmsWebApp() {
    val store = remember { WebPortalStore() }
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (store.tryRestoreSession()) {
            store.reloadCoreData { error ->
                if (error != null) {
                    store.signOut()
                    store.notice = error
                }
            }
        }
        restoring = false
    }

    when {
        restoring -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(EkmsColors.Paper),
                contentAlignment = Alignment.Center,
            ) {
                Text("Restoring session…", color = EkmsColors.Muted)
            }
        }
        store.signedIn -> WebPortal(store = store)
        else -> WebLoginScreen(
            onSignIn = { account, password, setError ->
                scope.launch {
                    try {
                        val login = ApiClient.login(account, password)
                        store.onAuthenticated(login)
                        store.reloadCoreData { error -> setError(error) }
                    } catch (error: Throwable) {
                        setError(error.message ?: "Unable to sign in.")
                    }
                }
            },
        )
    }
}

@Composable
private fun WebLoginScreen(onSignIn: (String, String, (String?) -> Unit) -> Unit) {
    var company by remember { mutableStateOf("Cavotec Malaysia") }
    var account by remember { mutableStateOf("superadmin@ekms.local") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(EkmsColors.AccentSoft, EkmsColors.Paper, EkmsColors.Wash),
                ),
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = EkmsColors.Panel),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            border = BorderStroke(1.dp, EkmsColors.Hairline),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 32.dp, vertical = 36.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "eKMS",
                    style = MaterialTheme.typography.displayMedium.copy(fontFamily = EkmsFonts.serif),
                )
                Text(
                    "Electronic Key Management",
                    style = MaterialTheme.typography.titleMedium,
                    color = EkmsColors.Muted,
                )
                Spacer(Modifier.height(4.dp))
                LoginForm(
                    company = company,
                    onCompanyChange = { company = it },
                    account = account,
                    onAccountChange = { account = it },
                    password = password,
                    onPasswordChange = { password = it },
                    error = error,
                    onSubmit = {
                        if (company.isBlank() || account.isBlank() || password.isBlank()) {
                            error = "Enter company, account and password to continue."
                        } else {
                            error = null
                            onSignIn(account, password) { error = it }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LoginForm(
    company: String,
    onCompanyChange: (String) -> Unit,
    account: String,
    onAccountChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    error: String?,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Sign in", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Authentication is handled by the backend. The Web client never stores the bootstrap password.",
            style = MaterialTheme.typography.bodyMedium,
            color = EkmsColors.Muted,
        )
        OutlinedTextField(
            value = company,
            onValueChange = onCompanyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Company / organisation") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
        )
        OutlinedTextField(
            value = account,
            onValueChange = onAccountChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Account email") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = error != null,
            shape = MaterialTheme.shapes.small,
            supportingText = {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            },
        )
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = EkmsColors.Accent,
                contentColor = Color.White,
            ),
        ) {
            Text("Sign in", fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Only the deployment-created Super Admin is preset. New personnel and keys are created from the management portal after sign-in.",
            style = MaterialTheme.typography.bodySmall,
            color = EkmsColors.Muted,
        )
    }
}

@Composable
private fun WebPortal(store: WebPortalStore) {
    var sidebarCollapsed by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val compact = maxWidth < 920.dp
        Column(modifier = Modifier.fillMaxSize()) {
            PortalTopBar(
                currentRoute = store.route,
                displayName = store.signedInDisplayName,
                sidebarCollapsed = sidebarCollapsed,
                showSidebarToggle = !compact,
                onToggleSidebar = { sidebarCollapsed = !sidebarCollapsed },
                onSignOut = {
                    store.signOut()
                    store.route = WebRoute.DASHBOARD
                },
            )
            if (compact) {
                CompactRoutePicker(
                    selected = store.route,
                    onRouteSelected = { store.route = it },
                )
                HorizontalDivider(color = EkmsColors.Hairline)
                PortalContent(
                    store = store,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    PortalSidebar(
                        selected = store.route,
                        collapsed = sidebarCollapsed,
                        onRouteSelected = { store.route = it },
                        onToggleCollapsed = { sidebarCollapsed = !sidebarCollapsed },
                        modifier = Modifier
                            .width(if (sidebarCollapsed) 76.dp else 268.dp)
                            .fillMaxHeight(),
                    )
                    Spacer(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(EkmsColors.Hairline),
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
private fun PortalTopBar(
    currentRoute: WebRoute,
    displayName: String,
    sidebarCollapsed: Boolean,
    showSidebarToggle: Boolean,
    onToggleSidebar: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(EkmsColors.TopBar)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showSidebarToggle) {
                IconButton(onClick = onToggleSidebar) {
                    Icon(
                        imageVector = if (sidebarCollapsed) NavIcons.Menu else NavIcons.MenuOpen,
                        contentDescription = if (sidebarCollapsed) "Expand navigation" else "Collapse navigation",
                        tint = EkmsColors.Accent,
                    )
                }
            }
            if (!sidebarCollapsed || !showSidebarToggle) {
                Text(
                    "eKMS",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = EkmsFonts.serif,
                        color = EkmsColors.Ink,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    currentRoute.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EkmsColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!sidebarCollapsed || !showSidebarToggle) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(displayName, style = MaterialTheme.typography.labelLarge, color = EkmsColors.Ink)
                    Text(
                        "Website management portal",
                        style = MaterialTheme.typography.labelSmall,
                        color = EkmsColors.Muted,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = onSignOut) {
                Text("Sign out", color = EkmsColors.Accent)
            }
        }
        HorizontalDivider(color = EkmsColors.Hairline, thickness = 1.dp)
    }
}

@Composable
private fun PortalSidebar(
    selected: WebRoute,
    collapsed: Boolean,
    onRouteSelected: (WebRoute) -> Unit,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val expandedGroups = remember {
        mutableStateMapOf<String, Boolean>().also { map ->
            WebNavigationGroups.forEach { map[it.title] = true }
        }
    }

    LaunchedEffect(selected) {
        WebNavigationGroups.firstOrNull { selected in it.routes }?.let { group ->
            expandedGroups[group.title] = true
        }
    }

    Row(
        modifier = modifier.background(EkmsColors.Panel),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.SpaceBetween,
            ) {
                if (!collapsed) {
                    Text(
                        "eKMS",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = EkmsFonts.serif,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = EkmsColors.Ink,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                IconButton(onClick = onToggleCollapsed) {
                    Icon(
                        imageVector = if (collapsed) NavIcons.Menu else NavIcons.MenuOpen,
                        contentDescription = if (collapsed) "Expand navigation" else "Collapse navigation",
                        tint = EkmsColors.Accent,
                    )
                }
            }
            HorizontalDivider(color = EkmsColors.Hairline)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = if (collapsed) 6.dp else 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                WebNavigationGroups.forEach { group ->
                    val groupExpanded = expandedGroups[group.title] != false
                    if (!collapsed) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    expandedGroups[group.title] = !groupExpanded
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                group.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = EkmsColors.Muted,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(
                                imageVector = if (groupExpanded) NavIcons.ExpandMore else NavIcons.ChevronRight,
                                contentDescription = if (groupExpanded) "Collapse ${group.title}" else "Expand ${group.title}",
                                tint = EkmsColors.Muted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                    }

                    if (collapsed || groupExpanded) {
                        group.routes.forEach { route ->
                            val active = route == selected
                            Row(
                                modifier = Modifier
                                    .then(if (collapsed) Modifier else Modifier.fillMaxWidth())
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (active) EkmsColors.AccentSoft else Color.Transparent)
                                    .clickable { onRouteSelected(route) }
                                    .padding(
                                        horizontal = if (collapsed) 8.dp else 6.dp,
                                        vertical = 8.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (active) EkmsColors.Accent else EkmsColors.Wash),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = route.navIcon,
                                        contentDescription = route.label,
                                        tint = if (active) Color.White else EkmsColors.Ink,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                if (!collapsed) {
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        route.label,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (active) EkmsColors.Ink else EkmsColors.Muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!collapsed) {
            SideScrollbar(
                scrollState = scrollState,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(14.dp),
            )
        }
    }
}

private val WebRoute.navIcon: ImageVector
    get() = when (this) {
        WebRoute.DASHBOARD -> NavIcons.Dashboard
        WebRoute.UNITS -> NavIcons.Apartment
        WebRoute.TERMINALS -> NavIcons.Devices
        WebRoute.PERSONNEL -> NavIcons.People
        WebRoute.KEYS -> NavIcons.Key
        WebRoute.PERMISSIONS -> NavIcons.Lock
        WebRoute.EVENTS -> NavIcons.Event
        WebRoute.SCHEDULES -> NavIcons.Schedule
        WebRoute.MULTI_AUTH_RULES -> NavIcons.Security
        WebRoute.USER_GROUPS -> NavIcons.Groups
        WebRoute.KEY_GROUPS -> NavIcons.Category
        WebRoute.DATA_SYNC -> NavIcons.Sync
        WebRoute.KEY_RECORDS -> NavIcons.Swap
        WebRoute.OPERATION_LOGS -> NavIcons.List
        WebRoute.APPOINTMENTS -> NavIcons.Event
        WebRoute.APPOINTMENT_REASONS -> NavIcons.Notes
        WebRoute.APPOINTMENT_PERMISSIONS -> NavIcons.Verified
        WebRoute.SYSTEM_LOGS -> NavIcons.Settings
        WebRoute.EQUIPMENT_LOGS -> NavIcons.Build
        WebRoute.RECYCLE_BIN -> NavIcons.Delete
    }

@Composable
private fun SideScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        modifier = modifier
            .background(EkmsColors.Wash)
            .padding(horizontal = 3.dp, vertical = 6.dp),
    ) {
        val trackHeight = maxHeight
        val maxScroll = scrollState.maxValue
        val trackPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val visibleFraction = if (maxScroll <= 0) {
            1f
        } else {
            (trackPx / (trackPx + maxScroll.toFloat())).coerceIn(0.18f, 1f)
        }
        val thumbHeight = trackHeight * visibleFraction
        val travel = (trackHeight - thumbHeight).coerceAtLeast(0.dp)
        val thumbOffset = if (maxScroll <= 0) {
            0.dp
        } else {
            travel * (scrollState.value.toFloat() / maxScroll.toFloat())
        }

        fun scrollToThumbFraction(fraction: Float) {
            if (maxScroll <= 0) return
            val target = (fraction.coerceIn(0f, 1f) * maxScroll).toInt()
            scope.launch { scrollState.scrollTo(target) }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(maxScroll, trackPx) {
                    detectTapGestures { offset ->
                        if (maxScroll <= 0) return@detectTapGestures
                        val y = offset.y
                        val thumbTop = thumbOffset.toPx()
                        val thumbBottom = thumbTop + thumbHeight.toPx()
                        if (y < thumbTop || y > thumbBottom) {
                            val center = ((y - thumbHeight.toPx() / 2f) / travel.toPx()).coerceIn(0f, 1f)
                            scrollToThumbFraction(center)
                        }
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbHeight)
                    .offset(y = thumbOffset)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EkmsColors.Accent.copy(alpha = if (maxScroll > 0) 0.75f else 0.28f))
                    .pointerInput(maxScroll, trackPx) {
                        if (maxScroll <= 0) return@pointerInput
                        detectVerticalDragGestures { _, dragAmount ->
                            val travelPx = travel.toPx().coerceAtLeast(1f)
                            val delta = (dragAmount / travelPx) * maxScroll
                            val next = (scrollState.value + delta).toInt().coerceIn(0, maxScroll)
                            scope.launch { scrollState.scrollTo(next) }
                        }
                    },
            )
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
            .padding(horizontal = 28.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
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
    Text(
        if (store.apiConnected) "API connected · $API_BASE_URL" else "API disconnected · local preview records only",
        color = if (store.apiConnected) EkmsColors.Online else EkmsColors.Offline,
        style = MaterialTheme.typography.bodyMedium,
    )
    MetricCards(
        listOf(
            DashboardMetric("Active keys", activeKeys.toString(), "Registered logical key records"),
            DashboardMetric("Assigned cabinet slots", assignedSlots.toString(), "Keys mapped to a Box/Node address"),
            DashboardMetric("Online terminals", "${onlineTerminals}/${store.terminals.size}", "Terminal status from backend"),
            DashboardMetric("Pending approvals", store.appointments.count { it.status == AppointmentStatus.PENDING }.toString(), "Appointments needing review"),
        ),
    )
    SoftSurfaceCard {
        Text("Cabinet overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "The Website shows status, records and requested synchronisation. Physical door/key-peg actions stay exclusively on an authorised Android Terminal.",
            style = MaterialTheme.typography.bodyMedium,
            color = EkmsColors.Muted,
        )
        if (store.terminals.isEmpty()) {
            Text("No terminals registered yet.", style = MaterialTheme.typography.bodyMedium, color = EkmsColors.Muted)
        }
        store.terminals.forEach { terminal ->
            HorizontalDivider(color = EkmsColors.Hairline)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(terminal.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${store.siteName(terminal.siteId)} · ${terminal.nodeLayout.label} · ${terminal.nodeCount} nodes",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StatusLabel(
                    text = "${terminal.status.label} · ${terminal.lastSyncLabel}",
                    online = terminal.status == PortalTerminalStatus.ONLINE,
                )
            }
        }
    }
    SoftSurfaceCard {
        Text("Quick actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { store.route = WebRoute.APPOINTMENTS },
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(containerColor = EkmsColors.Accent, contentColor = Color.White),
            ) { Text("Review appointments") }
            OutlinedButton(
                onClick = { store.route = WebRoute.DATA_SYNC },
                shape = MaterialTheme.shapes.small,
            ) { Text("Data sync") }
            OutlinedButton(
                onClick = { store.route = WebRoute.KEY_RECORDS },
                shape = MaterialTheme.shapes.small,
            ) { Text("Pickup & return") }
        }
    }
}

@Composable
private fun UnitsScreen(store: WebPortalStore) {
    var query by remember { mutableStateOf("") }
    val records = store.sites.filter {
        val superior = store.parentUnitName(it)
        it.name.matchesQuery(query) ||
            it.province.matchesQuery(query) ||
            it.city.matchesQuery(query) ||
            superior.matchesQuery(query)
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
                "Province / state: ${site.province.ifBlank { "—" }}",
                "City: ${site.city.ifBlank { "—" }}",
                "Superior unit: ${store.parentUnitName(site).ifBlank { "—" }}",
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
            OutlinedButton(onClick = { store.requestCredentialEnrollment(person) }) {
                Text("Request NFC enrollment")
            }
            if (person.accountStatus == PortalAccountStatus.ACTIVE) {
                OutlinedButton(onClick = { store.disablePerson(person) }) { Text("Disable account") }
            } else if (person.accountStatus == PortalAccountStatus.DISABLED) {
                OutlinedButton(onClick = { store.enablePerson(person) }) { Text("Enable account") }
            }
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
    QuietPanel("Unauthorized keys") {
        if (availableKeys.isEmpty()) Text("No additional active keys are available for this person.", color = EkmsColors.Muted)
        availableKeys.forEach { key ->
            HorizontalDivider(color = EkmsColors.Hairline)
            Text(key.displayName, fontWeight = FontWeight.SemiBold)
            Text("${store.keyTerminalName(key.id)} · ${store.keyLocationLabel(key.id)}", color = EkmsColors.Muted)
            OutlinedButton(onClick = { store.grantKey(activePerson.id, key.id) }, shape = MaterialTheme.shapes.small) { Text("Bind exact key") }
        }
    }
    QuietPanel("Authorized keys") {
        if (grants.isEmpty()) Text("No exact key permission is currently assigned.", color = EkmsColors.Muted)
        grants.forEach { grant ->
            grant.keyIds.forEach { keyId ->
                HorizontalDivider(color = EkmsColors.Hairline)
                Text(store.keyName(keyId), fontWeight = FontWeight.SemiBold)
                Text("Valid until: ${grant.validUntilEpochMillis.toDisplayDate() ?: "No expiry"}", color = EkmsColors.Muted)
                TextButton(onClick = { store.archiveAccessGrant(grant) }) { Text("Move to Recycle Bin") }
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
    QuietPanel("Select data scope") {
        Text(
            "Personnel, credentials, keys, slots, permissions, schedules, appointments and safe audit records are synchronised as revision-aware data.",
            color = EkmsColors.Muted,
        )
        Text("Raw fob UIDs, fingerprint templates and face templates are excluded from Web payloads.", color = EkmsColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { store.readFromTerminal(selectedTerminal) },
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(containerColor = EkmsColors.Accent, contentColor = Color.White),
            ) { Text("Read from terminal") }
            OutlinedButton(
                onClick = { store.downloadToTerminal(selectedTerminal) },
                shape = MaterialTheme.shapes.small,
            ) { Text("Download to terminal") }
        }
    }
    QuietPanel("Conflict review") {
        Text(
            "${store.openConflictCount} offline edit conflict${if (store.openConflictCount == 1) "" else "s"} awaiting Super Admin review.",
            color = EkmsColors.Muted,
        )
        store.syncConflicts.firstOrNull()?.let { conflict ->
            Text(
                "${conflict.entityType.name} ${conflict.entityId} · local op ${conflict.localChange.operationId} vs server revision ${conflict.serverRevision}.",
                color = EkmsColors.Muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { store.resolveConflict(ConflictResolutionStrategy.KEEP_SERVER) },
                    shape = MaterialTheme.shapes.small,
                ) { Text("Keep server") }
                OutlinedButton(
                    onClick = { store.resolveConflict(ConflictResolutionStrategy.KEEP_TERMINAL_CHANGE) },
                    shape = MaterialTheme.shapes.small,
                ) { Text("Keep terminal change") }
                OutlinedButton(
                    onClick = { store.resolveConflict(ConflictResolutionStrategy.MERGE_MANUALLY) },
                    shape = MaterialTheme.shapes.small,
                ) { Text("Merge manually") }
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
    QuietPanel("${store.personName(appointment.personId)} · ${appointment.status.label}") {
        Text("Terminal: ${store.terminalName(appointment.terminalId)}", color = EkmsColors.Muted)
        Text("Window: ${appointment.pickupWindow}", color = EkmsColors.Muted)
        Text("Authorized keys", fontWeight = FontWeight.SemiBold)
        appointment.keyIds.forEach { keyId -> Text("• ${store.keyName(keyId)}") }
    }
    QuietPanel("Unauthorized keys") {
        if (candidates.isEmpty()) Text("No additional key is available in this appointment's unit.", color = EkmsColors.Muted)
        candidates.forEach { key ->
            HorizontalDivider(color = EkmsColors.Hairline)
            Text(key.displayName, fontWeight = FontWeight.SemiBold)
            Text("${store.keyTerminalName(key.id)} · ${store.keyLocationLabel(key.id)}", color = EkmsColors.Muted)
            OutlinedButton(onClick = { store.addAppointmentKey(appointment.id, key.id) }, shape = MaterialTheme.shapes.small) {
                Text("Bind exact key")
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
    if (store.systemLogs.isEmpty()) EmptyState("No system audit events loaded yet. Sign in to load GET /v1/audit/events.")
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
    if (store.equipmentLogs.isEmpty()) EmptyState("No equipment audit events loaded yet.")
    store.equipmentLogs.forEach { log ->
        RecordCard(log.action, log.occurredAt, listOf("Terminal: ${log.actorOrTerminal}", "Node / scope: ${log.scope}"))
    }
}

@Composable
private fun RecycleBinScreen(store: WebPortalStore) {
    val isEmpty = store.recycleBinEntries.isEmpty()

    PageHeader(
        title = "Recycle Bin",
        description = "Super Admin only. Archived Units, Terminals, Personnel, Keys and Access Grants may be restored for ${RecycleBinPolicy.RETENTION_DAYS} days or permanently cleared earlier. Immutable audit history remains retained.",
        primaryLabel = "Purge expired",
        onPrimary = { store.purgeExpiredRecycleBin() },
    )
    if (isEmpty) EmptyState("No record is in the Recycle Bin.")
    store.recycleBinEntries.forEach { entry ->
        RecordCard(
            title = entry.recordLabel,
            status = entry.recordType.name,
            lines = listOf(
                "Restorable for $RECYCLE_RETENTION_LABEL from deletion.",
                "Revision ${entry.restorePayloadVersion}",
            ),
        ) {
            OutlinedButton(onClick = { store.restoreRecycleEntry(entry) }) { Text("Restore") }
            TextButton(onClick = { store.purgeRecycleEntry(entry) }) { Text("Clear permanently") }
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = EkmsColors.Muted)
        if (primaryLabel != null && onPrimary != null) {
            Button(
                onClick = onPrimary,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EkmsColors.Accent,
                    contentColor = Color.White,
                ),
            ) { Text(primaryLabel) }
        }
    }
}

@Composable
private fun NoticeBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, EkmsColors.Hairline, MaterialTheme.shapes.medium)
            .background(EkmsColors.AccentSoft)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun SoftSurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = EkmsColors.Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, EkmsColors.Hairline),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun MetricCards(metrics: List<DashboardMetric>) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth < 560.dp -> 1
            maxWidth < 900.dp -> 2
            else -> 4
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            metrics.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { metric ->
                        MetricCard(
                            metric = metric,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = EkmsColors.Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, EkmsColors.Hairline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(metric.label, style = MaterialTheme.typography.labelMedium, color = EkmsColors.Muted)
            Text(
                metric.value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = EkmsColors.AccentDark,
            )
            Text(metric.detail, style = MaterialTheme.typography.bodySmall, color = EkmsColors.Muted)
        }
    }
}

@Composable
private fun MetricStrip(metrics: List<DashboardMetric>) {
    MetricCards(metrics)
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                status?.let { StatusLabel(it) }
            }
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = EkmsColors.Muted)
            }
            actions()
        }
        HorizontalDivider(color = EkmsColors.Hairline)
    }
}

@Composable
private fun StatusLabel(text: String, online: Boolean? = null) {
    val tone = when {
        online == true -> EkmsColors.Online
        online == false -> EkmsColors.Offline
        text.contains("online", ignoreCase = true) || text.equals("Active", ignoreCase = true) -> EkmsColors.Online
        text.contains("offline", ignoreCase = true) || text.contains("pending", ignoreCase = true) -> EkmsColors.Offline
        else -> EkmsColors.Muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(tone, CircleShape),
        )
        Text(text, style = MaterialTheme.typography.labelMedium, color = tone)
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(
        message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = EkmsColors.Muted,
    )
}

@Composable
private fun QuietPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, EkmsColors.Hairline, MaterialTheme.shapes.medium)
            .background(EkmsColors.Panel)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun SafetyBoundaryCard(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, EkmsColors.Hairline, MaterialTheme.shapes.medium)
            .background(EkmsColors.Wash)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("Terminal-only hardware boundary", fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = EkmsColors.Muted)
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