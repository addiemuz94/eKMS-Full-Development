package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.AccountStatus
import com.ekms.shared.domain.AdminUser
import com.ekms.shared.domain.CredentialEnrollmentStatus
import com.ekms.shared.domain.ManagedSiteOption
import com.ekms.shared.domain.RecordLifecycle
import com.ekms.shared.domain.SuperAdminDemoData
import com.ekms.shared.domain.UserCredentialStatus
import com.ekms.shared.domain.UserDraft
import com.ekms.shared.domain.UserManagementPolicy
import com.ekms.shared.domain.UserRole
import com.ekms.shared.policy.RecycleBinPolicy
import kotlinx.datetime.Clock

private enum class TerminalAdminScreen {
    OVERVIEW,
    SITES_TERMINALS,
    KEYS_ACCESS,
    USERS,
    USER_EDITOR,
    USER_DETAILS,
    RECYCLE_BIN,
}

/**
 * On-site Super Admin UI. The local data is deliberately a preview repository;
 * it is replaced by the API/sync repository in the backend integration step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalAdminApp() {
    var screen by remember { mutableStateOf(TerminalAdminScreen.OVERVIEW) }
    var users by remember { mutableStateOf(SuperAdminDemoData.users()) }
    var selectedUserId by remember { mutableStateOf<String?>(null) }
    var editingUserId by remember { mutableStateOf<String?>(null) }

    val selectedUser = users.firstOrNull { it.id == selectedUserId }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0B5A73),
            secondary = Color(0xFF396B78),
        ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("eKMS Terminal · Super Admin") },
                )
            },
        ) { scaffoldPadding ->
            when (screen) {
                TerminalAdminScreen.OVERVIEW -> TerminalOverviewPage(
                    scaffoldPadding = scaffoldPadding,
                    activeUserCount = users.count { it.lifecycle.state == RecordLifecycle.ACTIVE },
                    binCount = users.count { it.lifecycle.state == RecordLifecycle.RECYCLE_BIN },
                    onOpenUsers = { screen = TerminalAdminScreen.USERS },
                    onOpenSitesAndTerminals = { screen = TerminalAdminScreen.SITES_TERMINALS },
                    onOpenKeysAndAccess = { screen = TerminalAdminScreen.KEYS_ACCESS },
                    onOpenRecycleBin = { screen = TerminalAdminScreen.RECYCLE_BIN },
                )

                TerminalAdminScreen.SITES_TERMINALS -> Box(
                    modifier = Modifier.padding(scaffoldPadding),
                ) {
                    SiteTerminalAdminScreen(
                        onBack = { screen = TerminalAdminScreen.OVERVIEW },
                    )
                }

                TerminalAdminScreen.KEYS_ACCESS -> Box(
                    modifier = Modifier.padding(scaffoldPadding),
                ) {
                    KeyAccessAdminScreen(
                        onBack = { screen = TerminalAdminScreen.OVERVIEW },
                    )
                }

                TerminalAdminScreen.USERS -> UserDirectoryPage(
                    scaffoldPadding = scaffoldPadding,
                    users = users.filter { it.lifecycle.state == RecordLifecycle.ACTIVE },
                    sites = SuperAdminDemoData.sites,
                    onAddUser = {
                        editingUserId = null
                        screen = TerminalAdminScreen.USER_EDITOR
                    },
                    onOpenUser = { userId ->
                        selectedUserId = userId
                        screen = TerminalAdminScreen.USER_DETAILS
                    },
                    onOpenRecycleBin = { screen = TerminalAdminScreen.RECYCLE_BIN },
                    onBack = { screen = TerminalAdminScreen.OVERVIEW },
                )

                TerminalAdminScreen.USER_EDITOR -> UserEditorPage(
                    scaffoldPadding = scaffoldPadding,
                    existingUser = users.firstOrNull { it.id == editingUserId },
                    sites = SuperAdminDemoData.sites,
                    onSave = { draft ->
                        val now = Clock.System.now().toEpochMilliseconds()
                        val existing = users.firstOrNull { it.id == editingUserId }
                        val savedUser = existing?.let {
                            UserManagementPolicy.updateUser(it, draft, now)
                        } ?: UserManagementPolicy.createUser(
                            id = "local_user_${now}_${users.size}",
                            draft = draft,
                            nowEpochMillis = now,
                        )

                        users = if (existing == null) {
                            users + savedUser
                        } else {
                            users.map { user ->
                                if (user.id == savedUser.id) savedUser else user
                            }
                        }
                        selectedUserId = savedUser.id
                        editingUserId = savedUser.id
                        screen = TerminalAdminScreen.USER_DETAILS
                    },
                    onBack = {
                        screen = if (editingUserId == null) {
                            TerminalAdminScreen.USERS
                        } else {
                            TerminalAdminScreen.USER_DETAILS
                        }
                    },
                )

                TerminalAdminScreen.USER_DETAILS -> {
                    val currentUser = selectedUser?.takeIf {
                        it.lifecycle.state == RecordLifecycle.ACTIVE
                    }
                    if (currentUser == null) {
                        PlaceholderPage(
                            scaffoldPadding = scaffoldPadding,
                            title = "User record unavailable",
                            onBack = { screen = TerminalAdminScreen.USERS },
                        )
                    } else {
                        UserDetailsPage(
                            scaffoldPadding = scaffoldPadding,
                            user = currentUser,
                            sites = SuperAdminDemoData.sites,
                            onEdit = {
                                editingUserId = currentUser.id
                                screen = TerminalAdminScreen.USER_EDITOR
                            },
                            onChangeAccountStatus = {
                                val now = Clock.System.now().toEpochMilliseconds()
                                val newStatus = if (currentUser.accountStatus == AccountStatus.ACTIVE) {
                                    AccountStatus.DISABLED
                                } else {
                                    AccountStatus.ACTIVE
                                }
                                users = users.map { user ->
                                    if (user.id == currentUser.id) {
                                        UserManagementPolicy.setAccountStatus(user, newStatus, now)
                                    } else {
                                        user
                                    }
                                }
                            },
                            onMoveToRecycleBin = {
                                val now = Clock.System.now().toEpochMilliseconds()
                                users = users.map { user ->
                                    if (user.id == currentUser.id) {
                                        UserManagementPolicy.moveToRecycleBin(
                                            existing = user,
                                            actorUserId = "usr_super_admin_demo",
                                            nowEpochMillis = now,
                                        )
                                    } else {
                                        user
                                    }
                                }
                                screen = TerminalAdminScreen.USERS
                            },
                            onBack = { screen = TerminalAdminScreen.USERS },
                        )
                    }
                }

                TerminalAdminScreen.RECYCLE_BIN -> RecycleBinPage(
                    scaffoldPadding = scaffoldPadding,
                    users = users.filter { it.lifecycle.state == RecordLifecycle.RECYCLE_BIN },
                    onRestore = { userId ->
                        val now = Clock.System.now().toEpochMilliseconds()
                        users = users.map { user ->
                            if (user.id == userId) {
                                UserManagementPolicy.restoreFromRecycleBin(user, now)
                            } else {
                                user
                            }
                        }
                    },
                    onPurge = { userId ->
                        val now = Clock.System.now().toEpochMilliseconds()
                        users = users.map { user ->
                            if (user.id == userId) {
                                UserManagementPolicy.permanentlyPurge(user, now)
                            } else {
                                user
                            }
                        }
                    },
                    onBack = { screen = TerminalAdminScreen.USERS },
                )

            }
        }
    }
}

@Composable
private fun TerminalOverviewPage(
    scaffoldPadding: PaddingValues,
    activeUserCount: Int,
    binCount: Int,
    onOpenUsers: () -> Unit,
    onOpenSitesAndTerminals: () -> Unit,
    onOpenKeysAndAccess: () -> Unit,
    onOpenRecycleBin: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding),
    ) {
        val sidePadding = responsiveSidePadding(maxWidth)
        val minCardWidth = if (maxWidth < 600.dp) 280.dp else 320.dp

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minCardWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = sidePadding,
                top = 20.dp,
                end = sidePadding,
                bottom = 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PageIntro(
                    title = "On-site administration",
                    description = "Responsive layout: cards automatically reflow for a terminal tablet, " +
                            "a smaller Android screen, or a landscape display. Keys, slots and access " +
                            "grants are managed without sending a cabinet command.",
                )
            }
            item {
                OverviewCard(
                    title = "Users & Credentials",
                    value = "$activeUserCount active users",
                    description = "Create, edit, disable and prepare credential enrolment.",
                    actionLabel = "Manage users",
                    onClick = onOpenUsers,
                )
            }
            item {
                OverviewCard(
                    title = "Sites & Terminals",
                    value = "Cabinet configuration",
                    description = "Manage sites, terminal identity, Box Address, node capacity and local sync setup.",
                    actionLabel = "Open Sites & Terminals",
                    onClick = onOpenSitesAndTerminals,
                )
            }
            item {
                OverviewCard(
                    title = "Keys, Slots & Access",
                    value = "Physical key configuration",
                    description = "Register key records, assign cabinet node addresses and grant exact user access.",
                    actionLabel = "Manage keys & access",
                    onClick = onOpenKeysAndAccess,
                )
            }
            item {
                OverviewCard(
                    title = "Recycle Bin",
                    value = "$binCount recoverable record(s)",
                    description = "Super Admin only · ${RecycleBinPolicy.RETENTION_DAYS}-day recovery window.",
                    actionLabel = "Open bin",
                    onClick = onOpenRecycleBin,
                )
            }
        }
    }
}

@Composable
private fun UserDirectoryPage(
    scaffoldPadding: PaddingValues,
    users: List<AdminUser>,
    sites: List<ManagedSiteOption>,
    onAddUser: () -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenRecycleBin: () -> Unit,
    onBack: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding),
    ) {
        val sidePadding = responsiveSidePadding(maxWidth)
        val minCardWidth = if (maxWidth < 600.dp) 280.dp else 340.dp

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minCardWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = sidePadding,
                top = 20.dp,
                end = sidePadding,
                bottom = 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PageIntro(
                    title = "Users & Credentials",
                    description = "This is local preview data. The final repository will call the documented " +
                            "Super Admin API and queue Terminal edits while offline.",
                    onBack = onBack,
                )
            }
            item {
                OverviewCard(
                    title = "Create user",
                    value = "Super Admin, Technician or Vendor",
                    description = "Assign the role and site scope before any credential is enrolled.",
                    actionLabel = "Add user",
                    onClick = onAddUser,
                )
            }
            item {
                OverviewCard(
                    title = "Recycle Bin",
                    value = "Restore or permanently clear",
                    description = "Deleted records are recoverable for ${RecycleBinPolicy.RETENTION_DAYS} days.",
                    actionLabel = "Open bin",
                    onClick = onOpenRecycleBin,
                )
            }
            items(users, key = { it.id }) { user ->
                UserCard(
                    user = user,
                    siteNames = siteNames(user.assignedSiteIds, sites),
                    onOpen = { onOpenUser(user.id) },
                )
            }
        }
    }
}

@Composable
private fun UserEditorPage(
    scaffoldPadding: PaddingValues,
    existingUser: AdminUser?,
    sites: List<ManagedSiteOption>,
    onSave: (UserDraft) -> Unit,
    onBack: () -> Unit,
) {
    var displayName by remember(existingUser?.id) {
        mutableStateOf(existingUser?.displayName.orEmpty())
    }
    var email by remember(existingUser?.id) {
        mutableStateOf(existingUser?.email.orEmpty())
    }
    var role by remember(existingUser?.id) {
        mutableStateOf(existingUser?.role ?: UserRole.TECHNICIAN)
    }
    var assignedSiteIds by remember(existingUser?.id) {
        mutableStateOf(existingUser?.assignedSiteIds ?: emptySet())
    }

    val draft = UserDraft(
        displayName = displayName,
        email = email,
        role = role,
        assignedSiteIds = assignedSiteIds,
    )
    val validationError = UserManagementPolicy.validateDraft(
        draft = draft,
        knownSiteIds = sites.mapTo(linkedSetOf()) { it.id },
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding),
    ) {
        val sidePadding = responsiveSidePadding(maxWidth)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = sidePadding,
                top = 20.dp,
                end = sidePadding,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PageIntro(
                    title = if (existingUser == null) "Create user" else "Edit user",
                    description = "A credential is never captured in this form. NFC and fingerprint enrolment " +
                            "will run only through a protected Terminal hardware action.",
                    onBack = onBack,
                )
            }
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Card(
                        modifier = Modifier
                            .widthIn(max = 720.dp)
                            .fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Full name") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Email") },
                                singleLine = true,
                            )
                            RoleSelector(
                                role = role,
                                onRoleSelected = { role = it },
                            )
                            Text(
                                text = "Assigned sites",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            sites.forEach { site ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = site.id in assignedSiteIds,
                                        onCheckedChange = { checked ->
                                            assignedSiteIds = if (checked) {
                                                assignedSiteIds + site.id
                                            } else {
                                                assignedSiteIds - site.id
                                            }
                                        },
                                    )
                                    Text(site.label)
                                }
                            }
                            if (validationError != null) {
                                Text(
                                    text = validationError,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(
                                onClick = { onSave(draft) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = validationError == null,
                            ) {
                                Text(if (existingUser == null) "Create user" else "Save changes")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserDetailsPage(
    scaffoldPadding: PaddingValues,
    user: AdminUser,
    sites: List<ManagedSiteOption>,
    onEdit: () -> Unit,
    onChangeAccountStatus: () -> Unit,
    onMoveToRecycleBin: () -> Unit,
    onBack: () -> Unit,
) {
    var showArchiveConfirmation by remember(user.id) { mutableStateOf(false) }

    if (showArchiveConfirmation) {
        AlertDialog(
            onDismissRequest = { showArchiveConfirmation = false },
            title = { Text("Move user to Recycle Bin?") },
            text = {
                Text(
                    "${user.displayName} will no longer be able to log in. A Super Admin can restore " +
                            "the record during the ${RecycleBinPolicy.RETENTION_DAYS}-day retention period.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showArchiveConfirmation = false
                        onMoveToRecycleBin()
                    },
                ) {
                    Text("Move to bin")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showArchiveConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding),
    ) {
        val sidePadding = responsiveSidePadding(maxWidth)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = sidePadding,
                top = 20.dp,
                end = sidePadding,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PageIntro(
                    title = user.displayName,
                    description = "${user.role.displayLabel()} · ${user.accountStatus.displayLabel()}",
                    onBack = onBack,
                )
            }
            item {
                ResponsiveDetailCard {
                    Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(user.email)
                    Text("Sites: ${siteNames(user.assignedSiteIds, sites)}")
                    Text("Account: ${user.accountStatus.displayLabel()}")
                }
            }
            item {
                ResponsiveDetailCard {
                    Text("Credential status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "The values below are safe status labels only. Credential secrets and biometric " +
                                "templates are never shown in this screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SuperAdminDemoData.credentialsFor(user).forEach { credential ->
                        CredentialStatusRow(credential)
                    }
                }
            }
            item {
                ResponsiveDetailCard {
                    Text("Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                        Text("Edit user")
                    }
                    Button(onClick = onChangeAccountStatus, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (user.accountStatus == AccountStatus.ACTIVE) {
                                "Disable user"
                            } else {
                                "Enable user"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { showArchiveConfirmation = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Move to Recycle Bin")
                    }
                }
            }
        }
    }
}

@Composable
private fun RecycleBinPage(
    scaffoldPadding: PaddingValues,
    users: List<AdminUser>,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onBack: () -> Unit,
) {
    var pendingPurgeUser by remember { mutableStateOf<AdminUser?>(null) }

    pendingPurgeUser?.let { user ->
        AlertDialog(
            onDismissRequest = { pendingPurgeUser = null },
            title = { Text("Permanently clear this user?") },
            text = {
                Text(
                    "This removes ${user.displayName} from the local Recycle Bin preview. " +
                            "Production permanent purge must preserve its immutable audit history.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingPurgeUser = null
                        onPurge(user.id)
                    },
                ) {
                    Text("Permanently clear")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingPurgeUser = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding),
    ) {
        val sidePadding = responsiveSidePadding(maxWidth)
        val minCardWidth = if (maxWidth < 600.dp) 280.dp else 340.dp

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minCardWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = sidePadding,
                top = 20.dp,
                end = sidePadding,
                bottom = 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PageIntro(
                    title = "Recycle Bin",
                    description = "Only Super Admin may restore or permanently clear records. " +
                            "Automatic retention is ${RecycleBinPolicy.RETENTION_DAYS} days.",
                    onBack = onBack,
                )
            }
            if (users.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ResponsiveDetailCard {
                        Text("The Recycle Bin is empty.")
                    }
                }
            } else {
                items(users, key = { it.id }) { user ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 210.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                user.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("${user.role.displayLabel()} · ${user.email}")
                            Text(
                                "Deleted by: ${user.lifecycle.deletedByUserId ?: "Unknown"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { onRestore(user.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Restore user")
                            }
                            OutlinedButton(
                                onClick = { pendingPurgeUser = user },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Permanently clear")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderPage(
    scaffoldPadding: PaddingValues,
    title: String,
    onBack: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(scaffoldPadding),
    ) {
        val sidePadding = responsiveSidePadding(maxWidth)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = sidePadding,
                top = 20.dp,
                end = sidePadding,
                bottom = 28.dp,
            ),
        ) {
            item {
                PageIntro(
                    title = title,
                    description = "This module follows after Users & Credentials. It will use the same " +
                            "responsive grid and offline-safe sync rules.",
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun OverviewCard(
    title: String,
    value: String,
    description: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun UserCard(
    user: AdminUser,
    siteNames: String,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 230.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(user.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${user.role.displayLabel()} · ${user.accountStatus.displayLabel()}")
            Text(user.email, style = MaterialTheme.typography.bodySmall)
            Text("Sites: $siteNames", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("Manage")
            }
        }
    }
}

@Composable
private fun RoleSelector(
    role: UserRole,
    onRoleSelected: (UserRole) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Role", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(role.displayLabel())
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            UserRole.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayLabel()) },
                    onClick = {
                        expanded = false
                        onRoleSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun PageIntro(
    title: String,
    description: String,
    onBack: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onBack != null) {
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ResponsiveDetailCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun CredentialStatusRow(credential: UserCredentialStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(credential.kind.displayLabel(), fontWeight = FontWeight.SemiBold)
            Text(
                "${credential.status.displayLabel()} · ${credential.detail}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun siteNames(siteIds: Set<String>, sites: List<ManagedSiteOption>): String =
    sites.filter { it.id in siteIds }.joinToString { it.label }.ifBlank { "No site assigned" }

private fun responsiveSidePadding(maxWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp = when {
    maxWidth < 600.dp -> 16.dp
    maxWidth < 1_000.dp -> 24.dp
    else -> 40.dp
}

private fun UserRole.displayLabel(): String = name.displayLabel()

private fun AccountStatus.displayLabel(): String = name.displayLabel()

private fun CredentialEnrollmentStatus.displayLabel(): String = name.displayLabel()

private fun com.ekms.shared.domain.CredentialKind.displayLabel(): String = name.displayLabel()

private fun String.displayLabel(): String =
    lowercase().split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }