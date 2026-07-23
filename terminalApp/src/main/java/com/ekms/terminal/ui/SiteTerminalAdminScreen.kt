package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.SiteEditorInput
import com.ekms.shared.domain.SiteTerminalField
import com.ekms.shared.domain.SiteTerminalManagementPolicy
import com.ekms.shared.domain.TerminalConnectionState
import com.ekms.shared.domain.TerminalEditorInput

/**
 * Step 3 on-site Super Admin screen.
 *
 * It is intentionally independent from CabinetGateway: changing a site or a
 * terminal record never sends a cabinet serial command. Backend sync is available
 * from Admin Menu once server address + terminal UUID are configured.
 * this local demo state in a later step.
 */
@Composable
fun SiteTerminalAdminScreen(onBack: () -> Unit) {
    val sites = remember {
        mutableStateListOf(
            LocalSite("site-kl", "Kuala Lumpur HQ", "Kuala Lumpur"),
            LocalSite("site-jb", "Johor Service Hub", "Johor Bahru"),
        )
    }
    val terminals = remember {
        mutableStateListOf(
            LocalTerminal(
                id = "terminal-kl-01",
                siteId = "site-kl",
                name = "HQ Main Cabinet",
                boxAddress = 1,
                serialNumber = "F7G18P-KL-001",
                slotCount = 48,
                serialPort = SiteTerminalManagementPolicy.DEFAULT_CABINET_SERIAL_PORT,
                baudRate = SiteTerminalManagementPolicy.DEFAULT_CABINET_BAUD_RATE,
                connectionState = TerminalConnectionState.ONLINE,
            ),
            LocalTerminal(
                id = "terminal-jb-01",
                siteId = "site-jb",
                name = "Service Cabinet",
                boxAddress = 1,
                serialNumber = "F7G18P-JB-001",
                slotCount = 24,
                serialPort = SiteTerminalManagementPolicy.DEFAULT_CABINET_SERIAL_PORT,
                baudRate = SiteTerminalManagementPolicy.DEFAULT_CABINET_BAUD_RATE,
                connectionState = TerminalConnectionState.SETUP_REQUIRED,
            ),
        )
    }
    val recycleBin = remember { mutableStateListOf<LocalBinEntry>() }
    var activeTab by remember { mutableStateOf(SiteTerminalTab.SITES) }
    var siteEditor by remember { mutableStateOf<SiteEditorState?>(null) }
    var terminalEditor by remember { mutableStateOf<TerminalEditorState?>(null) }
    var pendingPurge by remember { mutableStateOf<LocalBinEntry?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var nextId by remember { mutableIntStateOf(1) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 640.dp) 16.dp else 28.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1_100.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedButton(onClick = onBack) {
                Text("Back to Super Admin overview")
            }
            Text(
                text = "Sites & Terminals",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Create and maintain site records, terminal identity and cabinet connection settings. " +
                        "Use Admin Menu sync after setting the server address and Key Cabinet ID (backend terminal UUID).",
                style = MaterialTheme.typography.bodyMedium,
            )

            notice?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SiteTerminalTab.entries) { tab ->
                    if (tab == activeTab) {
                        Button(onClick = { activeTab = tab }) { Text(tab.label) }
                    } else {
                        OutlinedButton(onClick = { activeTab = tab }) { Text(tab.label) }
                    }
                }
            }

            when (activeTab) {
                SiteTerminalTab.SITES -> SitesContent(
                    sites = sites,
                    terminals = terminals,
                    onCreate = { siteEditor = SiteEditorState() },
                    onEdit = { siteEditor = SiteEditorState(it.id, it.name, it.address) },
                    onArchive = { site ->
                        if (terminals.any { it.siteId == site.id }) {
                            notice = "Archive the site's terminals first. A site is never deleted with hidden cascading changes."
                        } else {
                            sites.remove(site)
                            recycleBin.add(LocalBinEntry.SiteRecord(site))
                            notice = "${site.name} moved to the Super Admin Recycle Bin for 60 days."
                        }
                    },
                )

                SiteTerminalTab.TERMINALS -> TerminalsContent(
                    terminals = terminals,
                    sites = sites,
                    onCreate = {
                        if (sites.isEmpty()) {
                            notice = "Create or restore a site before creating a terminal."
                        } else {
                            terminalEditor = TerminalEditorState(siteId = sites.first().id)
                        }
                    },
                    onEdit = { terminal ->
                        terminalEditor = TerminalEditorState(
                            id = terminal.id,
                            siteId = terminal.siteId,
                            name = terminal.name,
                            boxAddress = terminal.boxAddress.toString(),
                            serialNumber = terminal.serialNumber,
                            slotCount = terminal.slotCount.toString(),
                            serialPort = terminal.serialPort,
                            baudRate = terminal.baudRate.toString(),
                            connectionState = terminal.connectionState,
                        )
                    },
                    onArchive = { terminal ->
                        terminals.remove(terminal)
                        recycleBin.add(LocalBinEntry.TerminalRecord(terminal))
                        notice = "${terminal.name} moved to the Super Admin Recycle Bin for 60 days."
                    },
                )

                SiteTerminalTab.RECYCLE_BIN -> RecycleBinContent(
                    recycleBin = recycleBin,
                    onRestore = { entry ->
                        when (entry) {
                            is LocalBinEntry.SiteRecord -> sites.add(entry.site)
                            is LocalBinEntry.TerminalRecord -> terminals.add(entry.terminal)
                        }
                        recycleBin.remove(entry)
                        notice = "${entry.label} restored."
                    },
                    onPurge = { pendingPurge = it },
                )
            }
        }
    }

    siteEditor?.let { editor ->
        SiteEditorDialog(
            editor = editor,
            onDismiss = { siteEditor = null },
            onSave = { saved ->
                val input = SiteEditorInput(saved.name, saved.address)
                if (SiteTerminalManagementPolicy.validateSite(input).isEmpty()) {
                    if (saved.id == null) {
                        val id = "site-local-$nextId"
                        nextId += 1
                        sites.add(LocalSite(id, saved.name.trim(), saved.address.trim()))
                        notice = "${saved.name.trim()} created locally."
                    } else {
                        val index = sites.indexOfFirst { it.id == saved.id }
                        if (index >= 0) {
                            sites[index] = LocalSite(saved.id, saved.name.trim(), saved.address.trim())
                            notice = "${saved.name.trim()} updated locally."
                        }
                    }
                    siteEditor = null
                }
            },
        )
    }

    terminalEditor?.let { editor ->
        TerminalEditorDialog(
            editor = editor,
            sites = sites,
            onDismiss = { terminalEditor = null },
            onSave = { saved ->
                val input = saved.toInput()
                if (SiteTerminalManagementPolicy.validateTerminal(input).isEmpty()) {
                    val terminal = LocalTerminal(
                        id = saved.id ?: "terminal-local-$nextId",
                        siteId = saved.siteId,
                        name = saved.name.trim(),
                        boxAddress = saved.boxAddress.trim().toInt(),
                        serialNumber = saved.serialNumber.trim(),
                        slotCount = saved.slotCount.trim().toInt(),
                        serialPort = saved.serialPort.trim(),
                        baudRate = saved.baudRate.trim().toInt(),
                        connectionState = saved.connectionState,
                    )
                    if (saved.id == null) {
                        nextId += 1
                        terminals.add(terminal)
                        notice = "${terminal.name} created locally."
                    } else {
                        val index = terminals.indexOfFirst { it.id == saved.id }
                        if (index >= 0) {
                            terminals[index] = terminal
                            notice = "${terminal.name} updated locally."
                        }
                    }
                    terminalEditor = null
                }
            },
        )
    }

    pendingPurge?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text("Permanently clear ${entry.label}?") },
            text = {
                Text(
                    "This removes the recoverable record now. Historical audit events remain retained.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        recycleBin.remove(entry)
                        notice = "${entry.label} permanently cleared from the local Recycle Bin."
                        pendingPurge = null
                    },
                ) {
                    Text("Clear permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPurge = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SitesContent(
    sites: List<LocalSite>,
    terminals: List<LocalTerminal>,
    onCreate: () -> Unit,
    onEdit: (LocalSite) -> Unit,
    onArchive: (LocalSite) -> Unit,
) {
    SectionHeader(
        title = "Sites",
        description = "A site is the owner of terminals, keys, access grants and audit filters.",
        actionLabel = "Add site",
        onAction = onCreate,
    )
    if (sites.isEmpty()) {
        EmptyState("No active sites. Restore one from the Recycle Bin or add a new site.")
    }
    sites.forEach { site ->
        val terminalCount = terminals.count { it.siteId == site.id }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(site.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(site.address.ifBlank { "Address not recorded" })
                Text("$terminalCount active terminal${if (terminalCount == 1) "" else "s"}")
                OutlinedButton(onClick = { onEdit(site) }) { Text("Edit site") }
                TextButton(onClick = { onArchive(site) }) { Text("Move to Recycle Bin") }
            }
        }
    }
}

@Composable
private fun TerminalsContent(
    terminals: List<LocalTerminal>,
    sites: List<LocalSite>,
    onCreate: () -> Unit,
    onEdit: (LocalTerminal) -> Unit,
    onArchive: (LocalTerminal) -> Unit,
) {
    SectionHeader(
        title = "Terminals & cabinet configuration",
        description = "Box Address and key-node capacity are stored as configuration only. This screen does not open a serial port or operate a lock.",
        actionLabel = "Add terminal",
        onAction = onCreate,
    )
    if (terminals.isEmpty()) {
        EmptyState("No active terminals. Add a terminal after creating its site.")
    }
    terminals.forEach { terminal ->
        val siteName = sites.firstOrNull { it.id == terminal.siteId }?.name ?: "Site unavailable"
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(terminal.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Site: $siteName")
                Text("Box Address ${terminal.boxAddress} · ${terminal.slotCount} configured key nodes")
                Text("${terminal.serialPort} · ${terminal.baudRate} baud")
                Text("Status: ${terminal.connectionState.label}")
                terminal.serialNumber.takeIf { it.isNotBlank() }?.let { Text("Serial number: $it") }
                OutlinedButton(onClick = { onEdit(terminal) }) { Text("Edit terminal") }
                TextButton(onClick = { onArchive(terminal) }) { Text("Move to Recycle Bin") }
            }
        }
    }
}

@Composable
private fun RecycleBinContent(
    recycleBin: List<LocalBinEntry>,
    onRestore: (LocalBinEntry) -> Unit,
    onPurge: (LocalBinEntry) -> Unit,
) {
    SectionHeader(
        title = "Recycle Bin",
        description = "Super Admin only. Records can be restored for ${com.ekms.shared.policy.RecycleBinPolicy.RETENTION_DAYS} days or cleared early.",
    )
    if (recycleBin.isEmpty()) {
        EmptyState("No sites or terminals are currently in the Recycle Bin.")
    }
    recycleBin.forEach { entry ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(entry.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${entry.typeLabel} · recoverable for 60 days in the production backend")
                OutlinedButton(onClick = { onRestore(entry) }) { Text("Restore") }
                TextButton(onClick = { onPurge(entry) }) { Text("Clear permanently") }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SiteEditorDialog(
    editor: SiteEditorState,
    onDismiss: () -> Unit,
    onSave: (SiteEditorState) -> Unit,
) {
    var form by remember(editor.id, editor.name, editor.address) { mutableStateOf(editor) }
    val issues = SiteTerminalManagementPolicy.validateSite(SiteEditorInput(form.name, form.address))
    val nameError = SiteTerminalManagementPolicy.errorFor(issues, SiteTerminalField.SITE_NAME)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.id == null) "Add site" else "Edit site") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { form = form.copy(name = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Site name") },
                    singleLine = true,
                    isError = nameError != null,
                )
                nameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = form.address,
                    onValueChange = { form = form.copy(address = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address / location") },
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (issues.isEmpty()) onSave(form) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TerminalEditorDialog(
    editor: TerminalEditorState,
    sites: List<LocalSite>,
    onDismiss: () -> Unit,
    onSave: (TerminalEditorState) -> Unit,
) {
    var form by remember(editor) { mutableStateOf(editor) }
    val issues = SiteTerminalManagementPolicy.validateTerminal(form.toInput())
    val selectedSite = sites.firstOrNull { it.id == form.siteId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.id == null) "Add terminal" else "Edit terminal") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { form = form.copy(name = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Terminal name") },
                    singleLine = true,
                    isError = SiteTerminalManagementPolicy.errorFor(issues, SiteTerminalField.TERMINAL_NAME) != null,
                )
                FieldError(issues, SiteTerminalField.TERMINAL_NAME)

                Text("Assigned site: ${selectedSite?.name ?: "Select a site"}")
                OutlinedButton(
                    enabled = sites.isNotEmpty(),
                    onClick = {
                        form = form.copy(siteId = nextSiteId(form.siteId, sites))
                    },
                ) {
                    Text("Choose next site")
                }
                FieldError(issues, SiteTerminalField.TERMINAL_SITE)

                OutlinedTextField(
                    value = form.boxAddress,
                    onValueChange = { form = form.copy(boxAddress = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cabinet Box Address (1–255)") },
                    singleLine = true,
                    isError = SiteTerminalManagementPolicy.errorFor(issues, SiteTerminalField.BOX_ADDRESS) != null,
                )
                FieldError(issues, SiteTerminalField.BOX_ADDRESS)

                OutlinedTextField(
                    value = form.slotCount,
                    onValueChange = { form = form.copy(slotCount = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Configured key-node count (1–255)") },
                    singleLine = true,
                    isError = SiteTerminalManagementPolicy.errorFor(issues, SiteTerminalField.SLOT_COUNT) != null,
                )
                FieldError(issues, SiteTerminalField.SLOT_COUNT)

                OutlinedTextField(
                    value = form.serialNumber,
                    onValueChange = { form = form.copy(serialNumber = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Terminal serial number (optional)") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = form.serialPort,
                    onValueChange = { form = form.copy(serialPort = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cabinet serial port") },
                    singleLine = true,
                    isError = SiteTerminalManagementPolicy.errorFor(issues, SiteTerminalField.CABINET_SERIAL_PORT) != null,
                )
                FieldError(issues, SiteTerminalField.CABINET_SERIAL_PORT)

                OutlinedTextField(
                    value = form.baudRate,
                    onValueChange = { form = form.copy(baudRate = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cabinet baud rate") },
                    singleLine = true,
                    isError = SiteTerminalManagementPolicy.errorFor(issues, SiteTerminalField.CABINET_BAUD_RATE) != null,
                )
                FieldError(issues, SiteTerminalField.CABINET_BAUD_RATE)

                Text("Connection status: ${form.connectionState.label}")
                OutlinedButton(
                    onClick = { form = form.copy(connectionState = form.connectionState.next()) },
                ) {
                    Text("Change status")
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (issues.isEmpty()) onSave(form) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FieldError(
    issues: List<com.ekms.shared.domain.SiteTerminalValidationIssue>,
    field: SiteTerminalField,
) {
    SiteTerminalManagementPolicy.errorFor(issues, field)?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private fun nextSiteId(currentSiteId: String, sites: List<LocalSite>): String {
    if (sites.isEmpty()) return ""
    val currentIndex = sites.indexOfFirst { it.id == currentSiteId }
    return sites[(currentIndex + 1 + sites.size) % sites.size].id
}

private enum class SiteTerminalTab(val label: String) {
    SITES("Sites"),
    TERMINALS("Terminals"),
    RECYCLE_BIN("Recycle Bin"),
}

private data class LocalSite(
    val id: String,
    val name: String,
    val address: String,
)

private data class LocalTerminal(
    val id: String,
    val siteId: String,
    val name: String,
    val boxAddress: Int,
    val serialNumber: String,
    val slotCount: Int,
    val serialPort: String,
    val baudRate: Int,
    val connectionState: TerminalConnectionState,
)

private sealed interface LocalBinEntry {
    val label: String
    val typeLabel: String

    data class SiteRecord(val site: LocalSite) : LocalBinEntry {
        override val label: String = site.name
        override val typeLabel: String = "Site"
    }

    data class TerminalRecord(val terminal: LocalTerminal) : LocalBinEntry {
        override val label: String = terminal.name
        override val typeLabel: String = "Terminal"
    }
}

private data class SiteEditorState(
    val id: String? = null,
    val name: String = "",
    val address: String = "",
)

private data class TerminalEditorState(
    val id: String? = null,
    val siteId: String = "",
    val name: String = "",
    val boxAddress: String = "1",
    val serialNumber: String = "",
    val slotCount: String = "24",
    val serialPort: String = SiteTerminalManagementPolicy.DEFAULT_CABINET_SERIAL_PORT,
    val baudRate: String = SiteTerminalManagementPolicy.DEFAULT_CABINET_BAUD_RATE.toString(),
    val connectionState: TerminalConnectionState = TerminalConnectionState.SETUP_REQUIRED,
) {
    fun toInput(): TerminalEditorInput = TerminalEditorInput(
        name = name,
        siteId = siteId,
        boxAddressText = boxAddress,
        serialNumber = serialNumber,
        slotCountText = slotCount,
        cabinetSerialPort = serialPort,
        cabinetBaudRateText = baudRate,
    )
}

private val TerminalConnectionState.label: String
    get() = when (this) {
        TerminalConnectionState.UNKNOWN -> "Unknown"
        TerminalConnectionState.ONLINE -> "Online"
        TerminalConnectionState.OFFLINE -> "Offline"
        TerminalConnectionState.SETUP_REQUIRED -> "Setup required"
    }

private fun TerminalConnectionState.next(): TerminalConnectionState = when (this) {
    TerminalConnectionState.UNKNOWN -> TerminalConnectionState.ONLINE
    TerminalConnectionState.ONLINE -> TerminalConnectionState.OFFLINE
    TerminalConnectionState.OFFLINE -> TerminalConnectionState.SETUP_REQUIRED
    TerminalConnectionState.SETUP_REQUIRED -> TerminalConnectionState.UNKNOWN
}