package com.ekms.web

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
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

private enum class WebScreen {
    OVERVIEW,
    USERS,
    USER_EDITOR,
    USER_DETAILS,
    RECYCLE_BIN,
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "root") {
        SuperAdminWebApp()
    }
}

/**
 * Web is the master operational view. This step intentionally uses local demo
 * data until the backend developer implements API_HANDOVER_SUPER_ADMIN.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuperAdminWebApp() {
    var screen by remember { mutableStateOf(WebScreen.OVERVIEW) }
    var users by remember { mutableStateOf(SuperAdminDemoData.users()) }
    var selectedUserId by remember { mutableStateOf<String?>(null) }
    var editingUserId by remember { mutableStateOf<String?>(null) }

    val selectedUser = users.firstOrNull { it.id == selectedUserId }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Electronic Key Management System · Super Admin") },
                )
            },
        ) { scaffoldPadding ->
            when (screen) {
                WebScreen.OVERVIEW -> WebOverviewPage(
                    scaffoldPadding = scaffoldPadding,
                    activeUserCount = users.count { it.lifecycle.state == RecordLifecycle.ACTIVE },
                    binCount = users.count { it.lifecycle.state == RecordLifecycle.RECYCLE_BIN },
                    onOpenUsers = { screen = WebScreen.USERS },
                    onOpenRecycleBin = { screen = WebScreen.RECYCLE_BIN },
                )

                WebScreen.USERS -> WebUserDirectoryPage(
                    scaffoldPadding = scaffoldPadding,
                    users = users.filter { it.lifecycle.state == RecordLifecycle.ACTIVE },
                    sites = SuperAdminDemoData.sites,
                    onAddUser = {
                        editingUserId = null
                        screen = WebScreen.USER_EDITOR
                    },
                    onOpenUser = { userId ->
                        selectedUserId = userId
                        screen = WebScreen.USER_DETAILS
                    },
                    onOpenRecycleBin = { screen = WebScreen.RECYCLE_BIN },
                    onBack = { screen = WebScreen.OVERVIEW },
                )

                WebScreen.USER_EDITOR -> WebUserEditorPage(
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
                            users.map { user -> if (user.id == savedUser.id) savedUser else user }
                        }
                        selectedUserId = savedUser.id
                        editingUserId = savedUser.id
                        screen = WebScreen.USER_DETAILS
                    },
                    onBack = {
                        screen = if (editingUserId == null) WebScreen.USERS else WebScreen.USER_DETAILS
                    },
                )

                WebScreen.USER_DETAILS -> {
                    val currentUser = selectedUser?.takeIf {
                        it.lifecycle.state == RecordLifecycle.ACTIVE
                    }
                    if (currentUser == null) {
                        WebUserDirectoryPage(
                            scaffoldPadding = scaffoldPadding,
                            users = users.filter { it.lifecycle.state == RecordLifecycle.ACTIVE },
                            sites = SuperAdminDemoData.sites,
                            onAddUser = {
                                editingUserId = null
                                screen = WebScreen.USER_EDITOR
                            },
                            onOpenUser = { userId -> selectedUserId = userId },
                            onOpenRecycleBin = { screen = WebScreen.RECYCLE_BIN },
                            onBack = { screen = WebScreen.OVERVIEW },
                        )
                    } else {
                        WebUserDetailsPage(
                            scaffoldPadding = scaffoldPadding,
                            user = currentUser,
                            sites = SuperAdminDemoData.sites,
                            onEdit = {
                                editingUserId = currentUser.id
                                screen = WebScreen.USER_EDITOR
                            },
                            onChangeAccountStatus = {
                                val now = Clock.System.now().toEpochMilliseconds()
                                val nextStatus = if (currentUser.accountStatus == AccountStatus.ACTIVE) {
                                    AccountStatus.DISABLED
                                } else {
                                    AccountStatus.ACTIVE
                                }
                                users = users.map { user ->
                                    if (user.id == currentUser.id) {
                                        UserManagementPolicy.setAccountStatus(user, nextStatus, now)
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
                                screen = WebScreen.USERS
                            },
                            onBack = { screen = WebScreen.USERS },
                        )
                    }
                }

                WebScreen.RECYCLE_BIN -> WebRecycleBinPage(
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
                    onBack = { screen = WebScreen.USERS },
                )
            }
        }
    }
}

@Composable
private fun WebOverviewPage(
    scaffoldPadding: PaddingValues,
    activeUserCount: Int,
    binCount: Int,
    onOpenUsers: () -> Unit,
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
                top = 24.dp,
                end = sidePadding,
                bottom = 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                WebPageIntro(
                    title = "Super Admin Control Centre",
                    description = "The dashboard reflows automatically between browser, tablet and compact " +
                            "mobile widths. The current step makes Users & Credentials functional.",
                )
            }
            item {
                WebActionCard(
                    title = "Users & Credentials",
                    value = "$activeUserCount active users",
                    description = "Create, edit, disable and assign site scope.",
                    actionLabel = "Manage users",
                    onClick = onOpenUsers,
                )
            }
            item {
                WebActionCard(
                    title = "Sites & Terminals",
                    value = "Next module",
                    description = "Site, cabinet, Box Address, node range and sync configuration.",
                    actionLabel = "Users first",
                    onClick = {},
                )
            }
            item {
                WebActionCard(
                    title = "Keys & Access",
                    value = "Next module",
                    description = "Exact user-to-key access, schedules and cabinet node assignments.",
                    actionLabel = "Users first",
                    onClick = {},
                )
            }
            item {
                WebActionCard(
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
private fun WebUserDirectoryPage(
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
                top = 24.dp,
                end = sidePadding,
                bottom = 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                WebPageIntro(
                    title = "Users & Credentials",
                    description = "Local preview data only. The backend API will become the source of truth; " +
                            "offline Terminal edits will return here for Super Admin conflict review.",
                    onBack = onBack,
                )
            }
            item {
                WebActionCard(
                    title = "Create user",
                    value = "Super Admin, Technician or Vendor",
                    description = "Assign a role and site scope before credential enrolment.",
                    actionLabel = "Add user",
                    onClick = onAddUser,
                )
            }
            item {
                WebActionCard(
                    title = "Recycle Bin",
                    value = "Restore or permanently clear",
                    description = "Deleted users remain recoverable for ${RecycleBinPolicy.RETENTION_DAYS} days.",
                    actionLabel = "Open bin",
                    onClick = onOpenRecycleBin,
                )
            }
            items(users, key = { it.id }) { user ->
                WebUserCard(
                    user = user,
                    siteNames = siteNames(user.assignedSiteIds, sites),
                    onOpen = { onOpenUser(user.id) },
                )
            }
        }
    }
}

@Composable
private fun WebUserEditorPage(
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
                top = 24.dp,
                end = sidePadding,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                WebPageIntro(
                    title = if (existingUser == null) "Create user" else "Edit user",
                    description = "Website assigns the credential workflow; NFC UID and fingerprint template " +
                            "capture occur only on the protected eKMS Terminal.",
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
                            .widthIn(max = 760.dp)
                            .fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
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
                            WebRoleSelector(role = role, onRoleSelected = { role = it })
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
private fun WebUserDetailsPage(
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
                    "${user.displayName} will be removed from active access. It remains recoverable " +
                            "to Super Admin for ${RecycleBinPolicy.RETENTION_DAYS} days.",
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
                top = 24.dp,
                end = sidePadding,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                WebPageIntro(
                    title = user.displayName,
                    description = "${user.role.displayLabel()} · ${user.accountStatus.displayLabel()}",
                    onBack = onBack,
                )
            }
            item {
                WebDetailCard {
                    Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(user.email)
                    Text("Sites: ${siteNames(user.assignedSiteIds, sites)}")
                    Text("Account: ${user.accountStatus.displayLabel()}")
                }
            }
            item {
                WebDetailCard {
                    Text("Credential workflow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "The Website may create an enrolment request, but it must never receive a raw NFC " +
                                "UID, passkey, or biometric template from the browser.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SuperAdminDemoData.credentialsFor(user).forEach { credential ->
                        WebCredentialStatusRow(credential)
                    }
                }
            }
            item {
                WebDetailCard {
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
private fun WebRecycleBinPage(
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
                    "This local preview removes ${user.displayName} from the Recycle Bin. The production " +
                            "backend must retain immutable audit history after purge.",
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
                top = 24.dp,
                end = sidePadding,
                bottom = 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                WebPageIntro(
                    title = "Recycle Bin",
                    description = "Super Admin only. Deleted records can be restored for " +
                            "${RecycleBinPolicy.RETENTION_DAYS} days or cleared earlier.",
                    onBack = onBack,
                )
            }
            if (users.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    WebDetailCard { Text("The Recycle Bin is empty.") }
                }
            } else {
                items(users, key = { it.id }) { user ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp),
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
private fun WebActionCard(
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
            modifier = Modifier.padding(20.dp),
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
private fun WebUserCard(
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
            modifier = Modifier.padding(20.dp),
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
private fun WebRoleSelector(
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
private fun WebPageIntro(
    title: String,
    description: String,
    onBack: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onBack != null) {
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WebDetailCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun WebCredentialStatusRow(credential: UserCredentialStatus) {
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
    maxWidth < 1_000.dp -> 28.dp
    else -> 48.dp
}

private fun UserRole.displayLabel(): String = name.displayLabel()

private fun AccountStatus.displayLabel(): String = name.displayLabel()

private fun CredentialEnrollmentStatus.displayLabel(): String = name.displayLabel()

private fun com.ekms.shared.domain.CredentialKind.displayLabel(): String = name.displayLabel()

private fun String.displayLabel(): String =
    lowercase().split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }