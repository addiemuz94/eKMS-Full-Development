package com.ekms.terminal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun StandbyScreen(
    padding: PaddingValues,
    settings: TerminalWorkflowSettings,
    keys: List<WorkflowKey>,
    latestRecord: WorkflowRecord?,
    onStartLogin: () -> Unit,
    onStartReturn: () -> Unit,
    onOpenStatus: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        val horizontalPadding = if (maxWidth < 640.dp) 20.dp else 40.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 860.dp)
                .align(Alignment.Center)
                .padding(horizontal = horizontalPadding, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "eKMS",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Electronic Key Management System",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onStartLogin),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Tap to sign in",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "NFC card, fingerprint, Digital Key, face recognition, or account password",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Return a key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Present the returned physical key fob to the reader. " +
                                "Identity verification is requested only when the Terminal setting requires it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onStartReturn, modifier = Modifier.fillMaxWidth()) {
                        Text("Start key return")
                    }
                }
            }

            if (settings.showHomeRealTimeStatus) {
                val presentCount = keys.count { it.status == WorkflowKeyStatus.PRESENT }
                val outCount = keys.count { it.status == WorkflowKeyStatus.OUT }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenStatus),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Real-time status", fontWeight = FontWeight.SemiBold)
                        Text("$presentCount key(s) available · $outCount key(s) out")
                        latestRecord?.let {
                            Text(
                                text = "Latest: ${it.title}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Text(
                text = "Terminal workflow preview · Hardware actions remain authorization-gated",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun LoginScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
    onDemoRegularLogin: () -> Unit,
    onDemoAdminLogin: () -> Unit,
) {
    var accountNumber by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    WorkflowPage(
        padding = padding,
        title = "Sign in",
        description = "Use your authorised eKMS credential. The login methods are kept together, as on the supplier Terminal workflow.",
        onBack = onBack,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Account password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = accountNumber,
                    onValueChange = { accountNumber = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("User number / account") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        notice = "Account verification is connected when the Terminal authentication repository is enabled. " +
                                "Use the clearly marked workflow test buttons below to review navigation safely."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Login")
                }
            }
        }

        Text("Or sign in with", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                LoginMethodCard("NFC card") {
                    notice = "NFC card authentication is available in this login area and will be connected to the authorised credential store."
                }
            }
            item {
                LoginMethodCard("Fingerprint") {
                    notice = "Fingerprint authentication is available in this login area and will be connected to the approved R503 credential workflow."
                }
            }
            item {
                LoginMethodCard("Digital Key") {
                    notice = "Digital Key remains a separate security workstream. Its issuer, expiry, offline rules, and anti-replay design must be confirmed before activation."
                }
            }
            item {
                LoginMethodCard("Face recognition") {
                    notice = "Face recognition is partially set. More updates are coming soon."
                }
            }
        }

        notice?.let { NoticeCard(it) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Workflow test only", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "These buttons let you verify the supplier-style navigation without pretending that a production credential was verified. They never send a cabinet command.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onDemoRegularLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("Test regular-user pickup flow")
                }
                OutlinedButton(onClick = onDemoAdminLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("Test administrator management menu")
                }
            }
        }
    }
}

@Composable
private fun LoginMethodCard(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun KeyPickupScreen(
    padding: PaddingValues,
    session: WorkflowSession,
    keys: List<WorkflowKey>,
    settings: TerminalWorkflowSettings,
    onBack: () -> Unit,
    onRequestPickup: (key: WorkflowKey, eventLabel: String, note: String) -> Unit,
) {
    var presentation by rememberSaveable { mutableStateOf(KeyPresentation.LAYOUT) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedKey by remember { mutableStateOf<WorkflowKey?>(null) }
    val filteredKeys = keys.filter { key ->
        key.displayName.contains(searchQuery, ignoreCase = true) ||
                key.nodeAddress.toString() == searchQuery.trim()
    }

    WorkflowPage(
        padding = padding,
        title = "Pick up keys",
        description = "Signed in as ${session.displayName}. Select an authorised available key.",
        onBack = onBack,
    ) {
        if (settings.pickupVideoRecording) {
            NoticeCard("Key pickup video recording is enabled in Terminal settings. Camera start/stop is connected only in the approved camera integration layer.")
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (presentation == KeyPresentation.LAYOUT) {
                    Button(onClick = { presentation = KeyPresentation.LAYOUT }) { Text("Layout display") }
                } else {
                    OutlinedButton(onClick = { presentation = KeyPresentation.LAYOUT }) { Text("Layout display") }
                }
            }
            item {
                if (presentation == KeyPresentation.LIST) {
                    Button(onClick = { presentation = KeyPresentation.LIST }) { Text("List display") }
                } else {
                    OutlinedButton(onClick = { presentation = KeyPresentation.LIST }) { Text("List display") }
                }
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search by key name or slot") },
            singleLine = true,
        )

        if (filteredKeys.isEmpty()) {
            EmptyWorkflowState("No key matches your search.")
        } else if (presentation == KeyPresentation.LAYOUT) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 210.dp, max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filteredKeys, key = { it.id }) { key ->
                    PickupKeyCard(
                        key = key,
                        permitted = mayPickUp(session, key),
                        onClick = { selectedKey = key },
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filteredKeys.forEach { key ->
                    PickupKeyListRow(
                        key = key,
                        permitted = mayPickUp(session, key),
                        onClick = { selectedKey = key },
                    )
                }
            }
        }

        Text(
            "A blue available key is selectable. A request follows the dual-authentication and event rules configured for that key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    selectedKey?.let { key ->
        KeyPickupRequestDialog(
            key = key,
            allowed = mayPickUp(session, key),
            onDismiss = { selectedKey = null },
            onSubmit = { eventLabel, note ->
                selectedKey = null
                onRequestPickup(key, eventLabel, note)
            },
        )
    }
}

@Composable
private fun PickupKeyCard(
    key: WorkflowKey,
    permitted: Boolean,
    onClick: () -> Unit,
) {
    val selectable = permitted && key.status == WorkflowKeyStatus.PRESENT && key.enabled
    val containerColor = when {
        selectable -> MaterialTheme.colorScheme.primaryContainer
        key.status == WorkflowKeyStatus.OUT -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectable) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            width = 1.dp,
            color = if (selectable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Slot ${key.nodeAddress}", style = MaterialTheme.typography.labelLarge)
            Text(
                text = key.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(key.status.label, style = MaterialTheme.typography.bodySmall)
            if (key.requiresDualAuthentication) {
                Text("Dual authentication", style = MaterialTheme.typography.bodySmall)
            }
            if (!permitted) {
                Text("No access", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PickupKeyListRow(
    key: WorkflowKey,
    permitted: Boolean,
    onClick: () -> Unit,
) {
    val selectable = permitted && key.status == WorkflowKeyStatus.PRESENT && key.enabled
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectable) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = if (selectable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${key.nodeAddress} · ${key.displayName}", fontWeight = FontWeight.SemiBold)
            Text("${key.status.label} · ${if (permitted) "Access granted" else "No access"}")
            key.overdueMinutes?.let { Text("Overdue alert after $it min", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun KeyPickupRequestDialog(
    key: WorkflowKey,
    allowed: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (eventLabel: String, note: String) -> Unit,
) {
    var eventLabel by remember { mutableStateOf("Routine work") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.displayName) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Slot ${key.nodeAddress} · ${key.status.label}")
                when {
                    !allowed -> Text("This user does not have permission for this key.", color = MaterialTheme.colorScheme.error)
                    !key.enabled -> Text("This key has been disabled by an administrator.", color = MaterialTheme.colorScheme.error)
                    key.status != WorkflowKeyStatus.PRESENT -> Text("Only an available key can be picked up.", color = MaterialTheme.colorScheme.error)
                    else -> {
                        if (key.requiresDualAuthentication) {
                            NoticeCard("This key requires a second authorised verifier before the pickup request is accepted.")
                        }
                        Text("Event")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("Routine work", "Inspection", "Emergency")) { choice ->
                                if (choice == eventLabel) {
                                    Button(onClick = { eventLabel = choice }) { Text(choice) }
                                } else {
                                    OutlinedButton(onClick = { eventLabel = choice }) { Text(choice) }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Remark (optional)") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(eventLabel, note) },
                enabled = allowed && key.enabled && key.status == WorkflowKeyStatus.PRESENT,
            ) {
                Text(if (key.requiresDualAuthentication) "Continue to verifier" else "Confirm pickup request")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun VerifierScreen(
    padding: PaddingValues,
    title: String,
    description: String,
    onBack: () -> Unit,
    onApproved: () -> Unit,
) {
    WorkflowPage(
        padding = padding,
        title = title,
        description = description,
        onBack = onBack,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Second identity verification", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "The production Terminal accepts an authorised verifier through account/password, NFC, fingerprint, or face recognition where approved. This workflow build provides no false authentication result.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { }) { Text("NFC card verifier") }
                OutlinedButton(onClick = { }) { Text("Fingerprint verifier") }
                OutlinedButton(onClick = { }) { Text("Account password verifier") }
                Button(onClick = onApproved, modifier = Modifier.fillMaxWidth()) {
                    Text("Workflow test: approve verifier")
                }
            }
        }
    }
}

@Composable
internal fun PickupStatusScreen(
    padding: PaddingValues,
    key: WorkflowKey?,
    onFinish: () -> Unit,
) {
    WorkflowPage(
        padding = padding,
        title = "Pickup request recorded",
        description = "${key?.displayName ?: "Selected key"} is waiting for the approved cabinet execution layer.",
    ) {
        NoticeCard(
            "No door, magnet, or key peg command was sent by this workflow screen. " +
                    "The audit record is a pickup request until the CabinetGateway implementation confirms the physical action.",
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Next physical action", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("When the cabinet execution layer is enabled, the selected slot will be highlighted and the approved release action will run here.")
                Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Finish") }
            }
        }
    }
}

@Composable
internal fun ReturnVerifierScreen(
    padding: PaddingValues,
    key: WorkflowKey?,
    onBack: () -> Unit,
    onApproved: () -> Unit,
) {
    VerifierScreen(
        padding = padding,
        title = "Verify key return",
        description = "${key?.displayName ?: "The returned key"} was identified. Terminal settings require identity verification before the return process continues.",
        onBack = onBack,
        onApproved = onApproved,
    )
}

@Composable
internal fun ReturnStatusScreen(
    padding: PaddingValues,
    key: WorkflowKey?,
    settings: TerminalWorkflowSettings,
    onConfirmReturn: () -> Unit,
    onFinish: () -> Unit,
) {
    WorkflowPage(
        padding = padding,
        title = "Return key",
        description = "${key?.displayName ?: "The returned key"} has been matched to its registered slot.",
    ) {
        if (settings.returnVideoRecording) {
            NoticeCard("Return video recording is enabled in Terminal settings. Camera execution is connected only in the approved camera integration layer.")
        }
        NoticeCard(
            "The supplier flow opens the correct door and illuminates the slot. This rework identifies the correct slot but does not operate the cabinet until the hardware execution layer is authorised and implemented.",
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Slot ${key?.nodeAddress ?: "-"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Insert the key only after the physical cabinet confirms the door/peg release.")
                Button(onClick = onConfirmReturn, modifier = Modifier.fillMaxWidth()) {
                    Text("Workflow test: confirm key inserted")
                }
                OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Finish without confirmation") }
            }
        }
    }
}

@Composable
internal fun ManagementMenuScreen(
    padding: PaddingValues,
    session: WorkflowSession,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenKeys: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenAppointments: () -> Unit,
    onOpenEvents: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPickup: () -> Unit,
    onOpenStatus: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        val horizontalPadding = if (maxWidth < 640.dp) 16.dp else 28.dp
        val cardMinWidth = if (maxWidth < 640.dp) 154.dp else 190.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = cardMinWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ManagementIntroCard(session = session, onBack = onBack)
            }
            item { ManagementTile("Terminal & Cabinet", "Terminal ID, nodes, server and cabinet configuration", onOpenTerminal) }
            item { ManagementTile("Key Registration", "Register all fobs or configure an individual key", onOpenKeys) }
            item { ManagementTile("Users", "View terminal users and credential readiness", onOpenUsers) }
            item { ManagementTile("Permissions", "Assign exact user-to-key access", onOpenPermissions) }
            item { ManagementTile("Records", "Pickup, return and all terminal records", onOpenRecords) }
            item { ManagementTile("Appointments", "Temporary key appointments", onOpenAppointments) }
            item { ManagementTile("Event", "Record a relevant operation event", onOpenEvents) }
            item { ManagementTile("Settings", "Return authentication, recording and sync settings", onOpenSettings) }
            item { ManagementTile("Take Keys", "Open the authorised pickup workflow", onOpenPickup) }
            item { ManagementTile("Real-time Status", "View current key and slot status", onOpenStatus) }
        }
    }
}

@Composable
private fun ManagementIntroCard(session: WorkflowSession, onBack: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Management Menu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Signed in as ${session.displayName}")
            Text("Administrator functions follow the supplier Terminal workflow while retaining the eKMS theme.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onBack) { Text("Sign out") }
        }
    }
}

@Composable
private fun ManagementTile(title: String, description: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Text("Open", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun KeyRegistrationScreen(
    padding: PaddingValues,
    keys: List<WorkflowKey>,
    onBack: () -> Unit,
    onOpenKey: (WorkflowKey) -> Unit,
    onRegisterAll: () -> Unit,
) {
    WorkflowPage(
        padding = padding,
        title = "Key Registration",
        description = "Register all physical key fobs in sequence, or open one slot for individual key registration.",
        onBack = onBack,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Register all keys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("The terminal will ask for one fob at a time, starting with the first unregistered or unenrolled key.")
                Button(onClick = onRegisterAll, modifier = Modifier.fillMaxWidth()) { Text("Start register-all sequence") }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(keys, key = { it.id }) { key ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenKey(key) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (key.fobEnrolled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("Slot ${key.nodeAddress}", style = MaterialTheme.typography.labelLarge)
                        Text(key.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (key.fobEnrolled) "Fob enrolled" else "Fob not enrolled", style = MaterialTheme.typography.bodySmall)
                        Text(key.status.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun KeyDetailsScreen(
    padding: PaddingValues,
    key: WorkflowKey,
    onBack: () -> Unit,
    onSave: (WorkflowKey) -> Unit,
    onStartFobEnrollment: () -> Unit,
) {
    var name by remember(key.id) { mutableStateOf(key.displayName) }
    var nodeAddressText by remember(key.id) { mutableStateOf(key.nodeAddress.toString()) }
    var overdueMinutesText by remember(key.id) { mutableStateOf(key.overdueMinutes?.toString().orEmpty()) }
    var requiresDualAuthentication by remember(key.id) { mutableStateOf(key.requiresDualAuthentication) }
    var enabled by remember(key.id) { mutableStateOf(key.enabled) }
    var nodeType by remember(key.id) { mutableStateOf(key.nodeType) }
    val nodeAddress = nodeAddressText.trim().toIntOrNull()
    val overdueMinutes = overdueMinutesText.trim().toIntOrNull()
    val nodeValid = nodeAddress != null && nodeAddress in 1..255
    val nameValid = name.trim().length >= 2

    WorkflowPage(
        padding = padding,
        title = "Key details",
        description = "Individual key registration and rule configuration for slot ${key.nodeAddress}.",
        onBack = onBack,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Key name") },
                    singleLine = true,
                    isError = !nameValid,
                )
                OutlinedTextField(
                    value = nodeAddressText,
                    onValueChange = { nodeAddressText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cabinet node address (1-255)") },
                    singleLine = true,
                    isError = nodeAddressText.isNotBlank() && !nodeValid,
                )
                OutlinedTextField(
                    value = overdueMinutesText,
                    onValueChange = { overdueMinutesText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Overdue time in minutes (optional)") },
                    singleLine = true,
                    isError = overdueMinutesText.isNotBlank() && (overdueMinutes == null || overdueMinutes <= 0),
                )
                ToggleRow(
                    title = "Dual authentication",
                    description = "Require a second authorised verifier before key pickup.",
                    checked = requiresDualAuthentication,
                    onCheckedChange = { requiresDualAuthentication = it },
                )
                ToggleRow(
                    title = "Key enabled",
                    description = "Disabled keys stay visible but cannot be selected for pickup.",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                Text("Node type", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(KeyNodeType.entries) { choice ->
                        if (choice == nodeType) {
                            Button(onClick = { nodeType = choice }) { Text(choice.label) }
                        } else {
                            OutlinedButton(onClick = { nodeType = choice }) { Text(choice.label) }
                        }
                    }
                }
                Text(
                    text = "Physical fob: ${if (key.fobEnrolled) "enrolled (reference protected)" else "not enrolled"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        onSave(
                            key.copy(
                                displayName = name.trim(),
                                nodeAddress = nodeAddress ?: key.nodeAddress,
                                overdueMinutes = overdueMinutes,
                                requiresDualAuthentication = requiresDualAuthentication,
                                enabled = enabled,
                                nodeType = nodeType,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = nameValid && nodeValid && (overdueMinutesText.isBlank() || (overdueMinutes != null && overdueMinutes > 0)),
                ) {
                    Text("Save key details")
                }
                OutlinedButton(onClick = onStartFobEnrollment, modifier = Modifier.fillMaxWidth()) {
                    Text(if (key.fobEnrolled) "Replace protected fob" else "Enrol physical fob")
                }
            }
        }
    }
}

@Composable
internal fun UsersScreen(
    padding: PaddingValues,
    users: List<WorkflowUser>,
    onBack: () -> Unit,
) {
    WorkflowPage(
        padding = padding,
        title = "Users",
        description = "User identities, roles and credential readiness. When automatic synchronization is enabled, production user creation belongs to the web platform.",
        onBack = onBack,
    ) {
        users.forEach { user ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(user.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(user.role.label)
                    Text(user.credentialSummary, style = MaterialTheme.typography.bodySmall)
                    user.multiAuthenticationGroup?.let {
                        Text("Verifier group: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (user.enabled) "Account enabled" else "Account disabled",
                        color = if (user.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PermissionsScreen(
    padding: PaddingValues,
    users: List<WorkflowUser>,
    keys: List<WorkflowKey>,
    selectedUserId: String?,
    onBack: () -> Unit,
    onSelectUser: (String) -> Unit,
    onTogglePermission: (keyId: String, userId: String, granted: Boolean) -> Unit,
) {
    val eligibleUsers = users.filter { it.role == WorkflowUserRole.REGULAR && it.enabled }
    val selectedUser = eligibleUsers.firstOrNull { it.id == selectedUserId } ?: eligibleUsers.firstOrNull()

    WorkflowPage(
        padding = padding,
        title = "Permission Assignment",
        description = "Choose a regular user, then grant or remove exact key access. Blue means access granted, matching the supplier workflow.",
        onBack = onBack,
    ) {
        if (eligibleUsers.isEmpty()) {
            EmptyWorkflowState("No enabled regular users are available for permission assignment.")
            return@WorkflowPage
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(eligibleUsers, key = { it.id }) { user ->
                if (user.id == selectedUser?.id) {
                    Button(onClick = { onSelectUser(user.id) }) { Text(user.displayName) }
                } else {
                    OutlinedButton(onClick = { onSelectUser(user.id) }) { Text(user.displayName) }
                }
            }
        }

        selectedUser?.let { user ->
            Text("${user.displayName} currently has ${keys.count { user.id in it.permittedUserIds }} key permission(s).")
            keys.forEach { key ->
                val granted = user.id in key.permittedUserIds
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Slot ${key.nodeAddress} · ${key.displayName}", fontWeight = FontWeight.SemiBold)
                        Text(if (granted) "Access granted" else "No access", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(
                            onClick = { onTogglePermission(key.id, user.id, !granted) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (granted) "Remove permission" else "Grant permission")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RecordsScreen(
    padding: PaddingValues,
    records: List<WorkflowRecord>,
    onBack: () -> Unit,
) {
    var showAllRecords by rememberSaveable { mutableStateOf(false) }
    val visibleRecords = if (showAllRecords) {
        records
    } else {
        records.filter {
            it.type == WorkflowRecordType.PICKUP_REQUESTED ||
                    it.type == WorkflowRecordType.PICKUP_COMPLETED ||
                    it.type == WorkflowRecordType.RETURN_REQUESTED ||
                    it.type == WorkflowRecordType.RETURN_COMPLETED
        }
    }

    WorkflowPage(
        padding = padding,
        title = "Records",
        description = "Switch between key pickup/return records and all Terminal records. Backend export and filters are connected in the later sync stage.",
        onBack = onBack,
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (!showAllRecords) {
                    Button(onClick = { showAllRecords = false }) { Text("Pickup / return") }
                } else {
                    OutlinedButton(onClick = { showAllRecords = false }) { Text("Pickup / return") }
                }
            }
            item {
                if (showAllRecords) {
                    Button(onClick = { showAllRecords = true }) { Text("All records") }
                } else {
                    OutlinedButton(onClick = { showAllRecords = true }) { Text("All records") }
                }
            }
        }
        if (visibleRecords.isEmpty()) {
            EmptyWorkflowState("No records match this view yet.")
        } else {
            visibleRecords.sortedByDescending { it.timestampEpochMillis }.forEach { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(record.title, fontWeight = FontWeight.SemiBold)
                        Text(record.type.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        Text(record.detail, style = MaterialTheme.typography.bodySmall)
                        Text(formatTimestamp(record.timestampEpochMillis), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppointmentsScreen(
    padding: PaddingValues,
    appointments: List<WorkflowAppointment>,
    users: List<WorkflowUser>,
    keys: List<WorkflowKey>,
    onBack: () -> Unit,
    onDeleteAppointment: (String) -> Unit,
) {
    WorkflowPage(
        padding = padding,
        title = "Appointment Authorization",
        description = "Temporary permission windows for an exact user and key. The final backend is authoritative for appointment creation and expiration.",
        onBack = onBack,
    ) {
        if (appointments.isEmpty()) {
            EmptyWorkflowState("No appointment authorization is scheduled.")
        } else {
            appointments.forEach { appointment ->
                val user = users.firstOrNull { it.id == appointment.userId }
                val key = keys.firstOrNull { it.id == appointment.keyId }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(key?.displayName ?: "Key unavailable", fontWeight = FontWeight.SemiBold)
                        Text("User: ${user?.displayName ?: "User unavailable"}")
                        Text("${formatTimestamp(appointment.startEpochMillis)} to ${formatTimestamp(appointment.endEpochMillis)}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onDeleteAppointment(appointment.id) }) { Text("Delete appointment") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EventScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
    onRecordEvent: (String) -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    WorkflowPage(
        padding = padding,
        title = "Event",
        description = "Record an operational event or note associated with the Terminal. This is an append-only local preview record until backend audit sync is connected.",
        onBack = onBack,
    ) {
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Event note") },
            minLines = 4,
        )
        Button(
            onClick = {
                onRecordEvent(note.trim())
                note = ""
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = note.trim().isNotEmpty(),
        ) {
            Text("Record event")
        }
    }
}

@Composable
internal fun SettingsScreen(
    padding: PaddingValues,
    settings: TerminalWorkflowSettings,
    onBack: () -> Unit,
    onUpdateSettings: (TerminalWorkflowSettings) -> Unit,
) {
    WorkflowPage(
        padding = padding,
        title = "Terminal Settings",
        description = "The supplier workflow settings are represented here. Hardware and backend settings take effect only through their approved integrations.",
        onBack = onBack,
    ) {
        ToggleRow(
            title = "Authentication for key returns",
            description = "Require a user identity check after the returned fob is identified.",
            checked = settings.requireReturnAuthentication,
            onCheckedChange = { onUpdateSettings(settings.copy(requireReturnAuthentication = it)) },
        )
        ToggleRow(
            title = "Home page real-time status",
            description = "Show present/absent status and last activity on standby.",
            checked = settings.showHomeRealTimeStatus,
            onCheckedChange = { onUpdateSettings(settings.copy(showHomeRealTimeStatus = it)) },
        )
        ToggleRow(
            title = "Key pickup video recording",
            description = "Request recording during pickup once camera integration is approved.",
            checked = settings.pickupVideoRecording,
            onCheckedChange = { onUpdateSettings(settings.copy(pickupVideoRecording = it)) },
        )
        ToggleRow(
            title = "Key return video recording",
            description = "Request recording during return once camera integration is approved.",
            checked = settings.returnVideoRecording,
            onCheckedChange = { onUpdateSettings(settings.copy(returnVideoRecording = it)) },
        )
        ToggleRow(
            title = "Upload login photo",
            description = "Enable only after the image-upload endpoint and consent policy are configured.",
            checked = settings.loginPhotoUpload,
            onCheckedChange = { onUpdateSettings(settings.copy(loginPhotoUpload = it)) },
        )
        ToggleRow(
            title = "Automatic synchronization",
            description = "When enabled in production, user administration moves to the Website source of truth.",
            checked = settings.autoSyncEnabled,
            onCheckedChange = { onUpdateSettings(settings.copy(autoSyncEnabled = it)) },
        )
        NoticeCard("Face recognition remains partially set. Its liveness and authorisation rules are not enabled from this setting screen.")
    }
}

@Composable
internal fun RealTimeStatusScreen(
    padding: PaddingValues,
    keys: List<WorkflowKey>,
    onBack: () -> Unit,
) {
    WorkflowPage(
        padding = padding,
        title = "Real-time Status",
        description = "Layout view of current local key states. When cabinet polling is connected, node telemetry will replace this preview state.",
        onBack = onBack,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 145.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 640.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(keys, key = { it.id }) { key ->
                val color = when (key.status) {
                    WorkflowKeyStatus.PRESENT -> MaterialTheme.colorScheme.primaryContainer
                    WorkflowKeyStatus.OUT -> MaterialTheme.colorScheme.surfaceVariant
                    WorkflowKeyStatus.UNREGISTERED -> MaterialTheme.colorScheme.surface
                    WorkflowKeyStatus.PICKUP_REQUESTED,
                    WorkflowKeyStatus.RETURN_REQUESTED,
                        -> MaterialTheme.colorScheme.secondaryContainer
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = color),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("Slot ${key.nodeAddress}", fontWeight = FontWeight.SemiBold)
                        Text(key.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(key.status.label, style = MaterialTheme.typography.bodySmall)
                        key.lastOperator?.let { Text("Last: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkflowPage(
    padding: PaddingValues,
    title: String,
    description: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        val horizontalPadding = if (maxWidth < 640.dp) 16.dp else 28.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1_080.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (onBack != null) {
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
internal fun NoticeCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EmptyWorkflowState(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(message, modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun mayPickUp(session: WorkflowSession, key: WorkflowKey): Boolean =
    session.isAdministrator || session.userId in key.permittedUserIds

private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))