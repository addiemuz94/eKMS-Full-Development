package com.ekms.web

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
import com.ekms.shared.domain.SiteTerminalValidationIssue
import com.ekms.shared.policy.RecycleBinPolicy

/** Responsive Website Super Admin view for Step 3. Backend calls are added in the sync step. */
@Composable
fun SiteTerminalWebScreen(onBack: () -> Unit) {
    val sites = remember {
        mutableStateListOf(
            WebSite("site-kl", "Kuala Lumpur HQ", "Kuala Lumpur"),
            WebSite("site-jb", "Johor Service Hub", "Johor Bahru"),
        )
    }
    val terminals = remember {
        mutableStateListOf(
            WebTerminal(
                id = "terminal-kl-01",
                siteId = "site-kl",
                name = "HQ Main Cabinet",
                boxAddress = 1,
                serialNumber = "F7G18P-KL-001",
                slotCount = 48,
                serialPort = SiteTerminalManagementPolicy.DEFAULT_CABINET_SERIAL_PORT,
                baudRate = SiteTerminalManagementPolicy.DEFAULT_CABINET_BAUD_RATE,
                state = TerminalConnectionState.ONLINE,
            ),
            WebTerminal(
                id = "terminal-jb-01",
                siteId = "site-jb",
                name = "Service Cabinet",
                boxAddress = 1,
                serialNumber = "F7G18P-JB-001",
                slotCount = 24,
                serialPort = SiteTerminalManagementPolicy.DEFAULT_CABINET_SERIAL_PORT,
                baudRate = SiteTerminalManagementPolicy.DEFAULT_CABINET_BAUD_RATE,
                state = TerminalConnectionState.SETUP_REQUIRED,
            ),
        )
    }
    val recycleBin = remember { mutableStateListOf<WebBinEntry>() }
    var tab by remember { mutableStateOf(WebManagementTab.SITES) }
    var siteEditor by remember { mutableStateOf<WebSiteEditor?>(null) }
    var terminalEditor by remember { mutableStateOf<WebTerminalEditor?>(null) }
    var purgeTarget by remember { mutableStateOf<WebBinEntry?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var nextId by remember { mutableIntStateOf(1) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth < 700.dp) 16.dp else 32.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1_200.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back to Super Admin dashboard") }
            Text("Sites & Terminals", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "The Website is the operational master view. Local demo changes shown here will be replaced by backend records and revision-aware sync.",
                style = MaterialTheme.typography.bodyMedium,
            )
            notice?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(message, modifier = Modifier.padding(14.dp))
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(WebManagementTab.entries) { option ->
                    if (option == tab) {
                        Button(onClick = { tab = option }) { Text(option.label) }
                    } else {
                        OutlinedButton(onClick = { tab = option }) { Text(option.label) }
                    }
                }
            }

            when (tab) {
                WebManagementTab.SITES -> {
                    WebSectionHeader(
                        title = "Sites",
                        description = "Full Super Admin site records. Delete moves a record to the protected Recycle Bin.",
                        actionLabel = "Add site",
                        onAction = { siteEditor = WebSiteEditor() },
                    )
                    if (sites.isEmpty()) WebEmptyState("No active sites.")
                    sites.forEach { site ->
                        val terminalCount = terminals.count { it.siteId == site.id }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(site.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(site.address.ifBlank { "Address not recorded" })
                                Text("$terminalCount active terminal${if (terminalCount == 1) "" else "s"}")
                                OutlinedButton(onClick = { siteEditor = WebSiteEditor(site.id, site.name, site.address) }) {
                                    Text("Edit site")
                                }
                                TextButton(
                                    onClick = {
                                        if (terminalCount > 0) {
                                            notice = "Archive the site's terminals first. The Website will never apply a hidden cascade delete."
                                        } else {
                                            sites.remove(site)
                                            recycleBin.add(WebBinEntry.SiteRecord(site))
                                            notice = "${site.name} moved to the Recycle Bin."
                                        }
                                    },
                                ) {
                                    Text("Move to Recycle Bin")
                                }
                            }
                        }
                    }
                }

                WebManagementTab.TERMINALS -> {
                    WebSectionHeader(
                        title = "Terminals & cabinet configuration",
                        description = "Every terminal is linked to one site. Box Address is 1–255 and node addresses remain exact cabinet protocol values.",
                        actionLabel = "Add terminal",
                        onAction = {
                            if (sites.isEmpty()) {
                                notice = "Create or restore a site before creating a terminal."
                            } else {
                                terminalEditor = WebTerminalEditor(siteId = sites.first().id)
                            }
                        },
                    )
                    if (terminals.isEmpty()) WebEmptyState("No active terminals.")
                    terminals.forEach { terminal ->
                        val siteName = sites.firstOrNull { it.id == terminal.siteId }?.name ?: "Site unavailable"
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(terminal.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("Site: $siteName")
                                Text("Box Address ${terminal.boxAddress} · ${terminal.slotCount} configured key nodes")
                                Text("${terminal.serialPort} · ${terminal.baudRate} baud")
                                Text("Status: ${terminal.state.webLabel}")
                                terminal.serialNumber.takeIf { it.isNotBlank() }?.let { Text("Serial number: $it") }
                                OutlinedButton(
                                    onClick = {
                                        terminalEditor = WebTerminalEditor(
                                            id = terminal.id,
                                            siteId = terminal.siteId,
                                            name = terminal.name,
                                            boxAddress = terminal.boxAddress.toString(),
                                            serialNumber = terminal.serialNumber,
                                            slotCount = terminal.slotCount.toString(),
                                            serialPort = terminal.serialPort,
                                            baudRate = terminal.baudRate.toString(),
                                            state = terminal.state,
                                        )
                                    },
                                ) {
                                    Text("Edit terminal")
                                }
                                TextButton(
                                    onClick = {
                                        terminals.remove(terminal)
                                        recycleBin.add(WebBinEntry.TerminalRecord(terminal))
                                        notice = "${terminal.name} moved to the Recycle Bin."
                                    },
                                ) {
                                    Text("Move to Recycle Bin")
                                }
                            }
                        }
                    }
                }

                WebManagementTab.RECYCLE_BIN -> {
                    WebSectionHeader(
                        title = "Recycle Bin",
                        description = "Super Admin only. Restore for ${RecycleBinPolicy.RETENTION_DAYS} days or permanently clear early.",
                    )
                    if (recycleBin.isEmpty()) WebEmptyState("No site or terminal records are in the Recycle Bin.")
                    recycleBin.forEach { entry ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(entry.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${entry.typeLabel} · production backend retains a 60-day restoration window")
                                OutlinedButton(
                                    onClick = {
                                        when (entry) {
                                            is WebBinEntry.SiteRecord -> sites.add(entry.site)
                                            is WebBinEntry.TerminalRecord -> terminals.add(entry.terminal)
                                        }
                                        recycleBin.remove(entry)
                                        notice = "${entry.label} restored."
                                    },
                                ) { Text("Restore") }
                                TextButton(onClick = { purgeTarget = entry }) { Text("Clear permanently") }
                            }
                        }
                    }
                }
            }
        }
    }

    siteEditor?.let { editor ->
        WebSiteEditorDialog(
            editor = editor,
            onDismiss = { siteEditor = null },
            onSave = { saved ->
                if (SiteTerminalManagementPolicy.validateSite(SiteEditorInput(saved.name, saved.address)).isEmpty()) {
                    if (saved.id == null) {
                        val id = "site-web-$nextId"
                        nextId += 1
                        sites.add(WebSite(id, saved.name.trim(), saved.address.trim()))
                        notice = "${saved.name.trim()} created locally."
                    } else {
                        val index = sites.indexOfFirst { it.id == saved.id }
                        if (index >= 0) sites[index] = WebSite(saved.id, saved.name.trim(), saved.address.trim())
                        notice = "${saved.name.trim()} updated locally."
                    }
                    siteEditor = null
                }
            },
        )
    }

    terminalEditor?.let { editor ->
        WebTerminalEditorDialog(
            editor = editor,
            sites = sites,
            onDismiss = { terminalEditor = null },
            onSave = { saved ->
                if (SiteTerminalManagementPolicy.validateTerminal(saved.toInput()).isEmpty()) {
                    val terminal = WebTerminal(
                        id = saved.id ?: "terminal-web-$nextId",
                        siteId = saved.siteId,
                        name = saved.name.trim(),
                        boxAddress = saved.boxAddress.trim().toInt(),
                        serialNumber = saved.serialNumber.trim(),
                        slotCount = saved.slotCount.trim().toInt(),
                        serialPort = saved.serialPort.trim(),
                        baudRate = saved.baudRate.trim().toInt(),
                        state = saved.state,
                    )
                    if (saved.id == null) {
                        nextId += 1
                        terminals.add(terminal)
                        notice = "${terminal.name} created locally."
                    } else {
                        val index = terminals.indexOfFirst { it.id == saved.id }
                        if (index >= 0) terminals[index] = terminal
                        notice = "${terminal.name} updated locally."
                    }
                    terminalEditor = null
                }
            },
        )
    }

    purgeTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { purgeTarget = null },
            title = { Text("Permanently clear ${entry.label}?") },
            text = { Text("The recoverable record is removed now; immutable audit history remains.") },
            confirmButton = {
                Button(
                    onClick = {
                        recycleBin.remove(entry)
                        notice = "${entry.label} permanently cleared."
                        purgeTarget = null
                    },
                ) { Text("Clear permanently") }
            },
            dismissButton = { TextButton(onClick = { purgeTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WebSectionHeader(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(description)
            if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun WebEmptyState(message: String) {
    Card(modifier = Modifier.fillMaxWidth()) { Text(message, modifier = Modifier.padding(16.dp)) }
}

@Composable
private fun WebSiteEditorDialog(
    editor: WebSiteEditor,
    onDismiss: () -> Unit,
    onSave: (WebSiteEditor) -> Unit,
) {
    var form by remember(editor) { mutableStateOf(editor) }
    val issues = SiteTerminalManagementPolicy.validateSite(SiteEditorInput(form.name, form.address))
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
                    isError = SiteTerminalManagementPolicy.errorFor(issues, SiteTerminalField.SITE_NAME) != null,
                )
                WebFieldError(issues, SiteTerminalField.SITE_NAME)
                OutlinedTextField(
                    value = form.address,
                    onValueChange = { form = form.copy(address = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address / location") },
                )
            }
        },
        confirmButton = { Button(onClick = { if (issues.isEmpty()) onSave(form) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WebTerminalEditorDialog(
    editor: WebTerminalEditor,
    sites: List<WebSite>,
    onDismiss: () -> Unit,
    onSave: (WebTerminalEditor) -> Unit,
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
                WebFieldError(issues, SiteTerminalField.TERMINAL_NAME)
                Text("Assigned site: ${selectedSite?.name ?: "Select a site"}")
                OutlinedButton(
                    enabled = sites.isNotEmpty(),
                    onClick = { form = form.copy(siteId = nextWebSiteId(form.siteId, sites)) },
                ) { Text("Choose next site") }
                WebFieldError(issues, SiteTerminalField.TERMINAL_SITE)
                WebTerminalField("Cabinet Box Address (1–255)", form.boxAddress, { form = form.copy(boxAddress = it) }, issues, SiteTerminalField.BOX_ADDRESS)
                WebTerminalField("Configured key-node count (1–255)", form.slotCount, { form = form.copy(slotCount = it) }, issues, SiteTerminalField.SLOT_COUNT)
                OutlinedTextField(
                    value = form.serialNumber,
                    onValueChange = { form = form.copy(serialNumber = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Terminal serial number (optional)") },
                    singleLine = true,
                )
                WebTerminalField("Cabinet serial port", form.serialPort, { form = form.copy(serialPort = it) }, issues, SiteTerminalField.CABINET_SERIAL_PORT)
                WebTerminalField("Cabinet baud rate", form.baudRate, { form = form.copy(baudRate = it) }, issues, SiteTerminalField.CABINET_BAUD_RATE)
                Text("Connection status: ${form.state.webLabel}")
                OutlinedButton(onClick = { form = form.copy(state = form.state.nextWebState()) }) { Text("Change status") }
            }
        },
        confirmButton = { Button(onClick = { if (issues.isEmpty()) onSave(form) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WebTerminalField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    issues: List<SiteTerminalValidationIssue>,
    field: SiteTerminalField,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = SiteTerminalManagementPolicy.errorFor(issues, field) != null,
    )
    WebFieldError(issues, field)
}

@Composable
private fun WebFieldError(issues: List<SiteTerminalValidationIssue>, field: SiteTerminalField) {
    SiteTerminalManagementPolicy.errorFor(issues, field)?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private fun nextWebSiteId(currentSiteId: String, sites: List<WebSite>): String {
    if (sites.isEmpty()) return ""
    val index = sites.indexOfFirst { it.id == currentSiteId }
    return sites[(index + 1 + sites.size) % sites.size].id
}

private enum class WebManagementTab(val label: String) {
    SITES("Sites"),
    TERMINALS("Terminals"),
    RECYCLE_BIN("Recycle Bin"),
}

private data class WebSite(val id: String, val name: String, val address: String)

private data class WebTerminal(
    val id: String,
    val siteId: String,
    val name: String,
    val boxAddress: Int,
    val serialNumber: String,
    val slotCount: Int,
    val serialPort: String,
    val baudRate: Int,
    val state: TerminalConnectionState,
)

private sealed interface WebBinEntry {
    val label: String
    val typeLabel: String

    data class SiteRecord(val site: WebSite) : WebBinEntry {
        override val label: String = site.name
        override val typeLabel: String = "Site"
    }

    data class TerminalRecord(val terminal: WebTerminal) : WebBinEntry {
        override val label: String = terminal.name
        override val typeLabel: String = "Terminal"
    }
}

private data class WebSiteEditor(val id: String? = null, val name: String = "", val address: String = "")

private data class WebTerminalEditor(
    val id: String? = null,
    val siteId: String = "",
    val name: String = "",
    val boxAddress: String = "1",
    val serialNumber: String = "",
    val slotCount: String = "24",
    val serialPort: String = SiteTerminalManagementPolicy.DEFAULT_CABINET_SERIAL_PORT,
    val baudRate: String = SiteTerminalManagementPolicy.DEFAULT_CABINET_BAUD_RATE.toString(),
    val state: TerminalConnectionState = TerminalConnectionState.SETUP_REQUIRED,
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

private val TerminalConnectionState.webLabel: String
    get() = when (this) {
        TerminalConnectionState.UNKNOWN -> "Unknown"
        TerminalConnectionState.ONLINE -> "Online"
        TerminalConnectionState.OFFLINE -> "Offline"
        TerminalConnectionState.SETUP_REQUIRED -> "Setup required"
    }

private fun TerminalConnectionState.nextWebState(): TerminalConnectionState = when (this) {
    TerminalConnectionState.UNKNOWN -> TerminalConnectionState.ONLINE
    TerminalConnectionState.ONLINE -> TerminalConnectionState.OFFLINE
    TerminalConnectionState.OFFLINE -> TerminalConnectionState.SETUP_REQUIRED
    TerminalConnectionState.SETUP_REQUIRED -> TerminalConnectionState.UNKNOWN
}