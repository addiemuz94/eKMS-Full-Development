package com.ekms.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.AccessGrant
import com.ekms.shared.domain.AccessGrantDraft
import com.ekms.shared.domain.AccountStatus
import com.ekms.shared.domain.AdminUser
import com.ekms.shared.domain.KeyAccessField
import com.ekms.shared.domain.KeyDraft
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.KeySlotAccessPolicy
import com.ekms.shared.domain.KeySlotDemoData
import com.ekms.shared.domain.KeySlotDraft
import com.ekms.shared.domain.LifecycleMetadata
import com.ekms.shared.domain.ManagedKey
import com.ekms.shared.domain.ManagedSiteOption
import com.ekms.shared.domain.ManagedTerminalOption
import com.ekms.shared.domain.RecordLifecycle
import com.ekms.shared.domain.SuperAdminDemoData
import com.ekms.shared.domain.UserRole
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private enum class KeyAccessTab(val label: String) {
    KEYS("Keys"),
    CABINET_SLOTS("Cabinet Slots"),
    ACCESS_GRANTS("Access Grants"),
    RECYCLE_BIN("Recycle Bin"),
}

private data class KeyEditorState(
    val id: String? = null,
    val displayName: String = "",
    val siteId: String = "",
)

private data class SlotEditorState(
    val id: String? = null,
    val terminalId: String = "",
    val nodeAddress: String = "",
    val keyId: String = "",
)

private data class GrantEditorState(
    val id: String? = null,
    val userId: String = "",
    val siteId: String = "",
    val keyIds: Set<String> = emptySet(),
    val validFrom: String = "",
    val validUntil: String = "",
)

private enum class RecycleItemKind {
    KEY,
    SLOT,
    GRANT,
}

private data class RecycleItem(
    val kind: RecycleItemKind,
    val id: String,
    val title: String,
    val detail: String,
)

private data class SelectionChoice(
    val id: String,
    val label: String,
)

/**
 * Step 4 Website Super Admin screen.
 *
 * It manages local demo records only. It intentionally does not import
 * CabinetGateway, open /dev/ttyS1, scan a tag, or operate a lock.
 */
@Composable
fun KeyAccessWebScreen(onBack: () -> Unit) {
    var keys by remember { mutableStateOf(KeySlotDemoData.keys()) }
    var slots by remember { mutableStateOf(KeySlotDemoData.slots()) }
    var grants by remember { mutableStateOf(KeySlotDemoData.accessGrants()) }
    var tab by remember { mutableStateOf(KeyAccessTab.KEYS) }
    var keyEditor by remember { mutableStateOf<KeyEditorState?>(null) }
    var slotEditor by remember { mutableStateOf<SlotEditorState?>(null) }
    var grantEditor by remember { mutableStateOf<GrantEditorState?>(null) }
    var pendingPurge by remember { mutableStateOf<RecycleItem?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val sites = SuperAdminDemoData.sites
    val terminals = KeySlotDemoData.terminals
    val users = SuperAdminDemoData.users()
    val activeKeys = keys.filter { it.lifecycle.state == RecordLifecycle.ACTIVE }
    val activeSlots = slots.filter { it.lifecycle.state == RecordLifecycle.ACTIVE }
    val activeGrants = grants.filter { it.lifecycle.state == RecordLifecycle.ACTIVE }
    val activeUsers = users.filter {
        it.lifecycle.state == RecordLifecycle.ACTIVE && it.accountStatus == AccountStatus.ACTIVE
    }

    fun now() = Clock.System.now().toEpochMilliseconds()

    fun archiveMetadata(existing: LifecycleMetadata) = existing.copy(
        state = RecordLifecycle.RECYCLE_BIN,
        updatedAtEpochMillis = now(),
        deletedAtEpochMillis = now(),
        deletedByUserId = "usr_super_admin_demo",
    )

    fun restoreMetadata(existing: LifecycleMetadata) = existing.copy(
        state = RecordLifecycle.ACTIVE,
        updatedAtEpochMillis = now(),
        deletedAtEpochMillis = null,
        deletedByUserId = null,
    )

    fun purgeMetadata(existing: LifecycleMetadata) = existing.copy(
        state = RecordLifecycle.PURGED,
        updatedAtEpochMillis = now(),
    )

    val recycleItems = buildList {
        keys.filter { it.lifecycle.state == RecordLifecycle.RECYCLE_BIN }.forEach { key ->
            add(
                RecycleItem(
                    kind = RecycleItemKind.KEY,
                    id = key.id,
                    title = key.displayName,
                    detail = "Key · ${siteLabel(key.siteId, sites)}",
                ),
            )
        }
        slots.filter { it.lifecycle.state == RecordLifecycle.RECYCLE_BIN }.forEach { slot ->
            add(
                RecycleItem(
                    kind = RecycleItemKind.SLOT,
                    id = slot.id,
                    title = "Node ${slot.nodeAddress}",
                    detail = "Cabinet slot · ${terminalLabel(slot.terminalId, terminals)}",
                ),
            )
        }
        grants.filter { it.lifecycle.state == RecordLifecycle.RECYCLE_BIN }.forEach { grant ->
            add(
                RecycleItem(
                    kind = RecycleItemKind.GRANT,
                    id = grant.id,
                    title = userLabel(grant.userId, users),
                    detail = "Access grant · ${grant.keyIds.size} key(s) · ${siteLabel(grant.siteId, sites)}",
                ),
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sidePadding = if (maxWidth < 640.dp) 16.dp else 28.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 1_100.dp)
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = sidePadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedButton(onClick = onBack) {
                Text("Back to Super Admin overview")
            }
            Text(
                text = "Keys, Cabinet Slots & Access",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Register physical key records, map them to exact cabinet key-node addresses and grant access. " +
                        "This local preview never sends a serial, lock or door command.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    text = "Address rule: Box Address belongs to the terminal configuration. " +
                            "Key slots use protocol node addresses 1 to the terminal's configured slot capacity. " +
                            "Door node 0 is intentionally excluded.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(KeyAccessTab.entries) { option ->
                    if (option == tab) {
                        Button(onClick = { tab = option }) { Text(option.label) }
                    } else {
                        OutlinedButton(onClick = { tab = option }) { Text(option.label) }
                    }
                }
            }

            notice?.let { message ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            when (tab) {
                KeyAccessTab.KEYS -> {
                    Button(
                        onClick = {
                            keyEditor = KeyEditorState(siteId = sites.firstOrNull()?.id.orEmpty())
                        },
                    ) {
                        Text("Register key")
                    }
                    if (activeKeys.isEmpty()) {
                        EmptyState("No active keys. Register a key before assigning a cabinet slot or access grant.")
                    } else {
                        activeKeys.forEach { key ->
                            KeyRecordCard(
                                key = key,
                                siteLabel = siteLabel(key.siteId, sites),
                                onEdit = {
                                    keyEditor = KeyEditorState(
                                        id = key.id,
                                        displayName = key.displayName,
                                        siteId = key.siteId,
                                    )
                                },
                                onArchive = {
                                    val usedBySlot = activeSlots.any { it.managedKeyId == key.id }
                                    val usedByGrant = activeGrants.any { key.id in it.keyIds }
                                    notice = when {
                                        usedBySlot -> "Remove ${key.displayName} from its active cabinet slot before moving it to the Recycle Bin."
                                        usedByGrant -> "Remove ${key.displayName} from its active access grants before moving it to the Recycle Bin."
                                        else -> {
                                            keys = keys.map {
                                                if (it.id == key.id) it.copy(lifecycle = archiveMetadata(it.lifecycle)) else it
                                            }
                                            "${key.displayName} moved to the Super Admin Recycle Bin."
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                KeyAccessTab.CABINET_SLOTS -> {
                    Button(
                        onClick = {
                            slotEditor = SlotEditorState(terminalId = terminals.firstOrNull()?.id.orEmpty())
                        },
                        enabled = terminals.isNotEmpty(),
                    ) {
                        Text("Register cabinet slot")
                    }
                    if (activeSlots.isEmpty()) {
                        EmptyState("No registered cabinet slots yet. A slot may be created empty, then assigned a key later.")
                    } else {
                        activeSlots.sortedWith(compareBy<KeySlot> { it.terminalId }.thenBy { it.nodeAddress }).forEach { slot ->
                            SlotRecordCard(
                                slot = slot,
                                terminalLabel = terminalLabel(slot.terminalId, terminals),
                                keyLabel = keyLabel(slot.managedKeyId, activeKeys),
                                onEdit = {
                                    slotEditor = SlotEditorState(
                                        id = slot.id,
                                        terminalId = slot.terminalId,
                                        nodeAddress = slot.nodeAddress.toString(),
                                        keyId = slot.managedKeyId.orEmpty(),
                                    )
                                },
                                onArchive = {
                                    slots = slots.map {
                                        if (it.id == slot.id) it.copy(lifecycle = archiveMetadata(it.lifecycle)) else it
                                    }
                                    notice = "Node ${slot.nodeAddress} moved to the Super Admin Recycle Bin."
                                },
                            )
                        }
                    }
                }

                KeyAccessTab.ACCESS_GRANTS -> {
                    Button(
                        onClick = {
                            grantEditor = GrantEditorState(
                                userId = activeUsers.firstOrNull()?.id.orEmpty(),
                                siteId = sites.firstOrNull()?.id.orEmpty(),
                            )
                        },
                        enabled = activeUsers.isNotEmpty() && activeKeys.isNotEmpty(),
                    ) {
                        Text("Create exact access grant")
                    }
                    if (activeGrants.isEmpty()) {
                        EmptyState("No access grants yet. Select one active user, one site and the exact keys they may take.")
                    } else {
                        activeGrants.forEach { grant ->
                            GrantRecordCard(
                                grant = grant,
                                userLabel = userLabel(grant.userId, users),
                                siteLabel = siteLabel(grant.siteId, sites),
                                keyLabels = grant.keyIds.map { keyLabel(it, activeKeys) },
                                onEdit = {
                                    grantEditor = GrantEditorState(
                                        id = grant.id,
                                        userId = grant.userId,
                                        siteId = grant.siteId,
                                        keyIds = grant.keyIds,
                                        validFrom = formatIsoDate(grant.validFromEpochMillis).orEmpty(),
                                        validUntil = formatIsoDate(grant.validUntilEpochMillis).orEmpty(),
                                    )
                                },
                                onArchive = {
                                    grants = grants.map {
                                        if (it.id == grant.id) it.copy(lifecycle = archiveMetadata(it.lifecycle)) else it
                                    }
                                    notice = "Access grant for ${userLabel(grant.userId, users)} moved to the Super Admin Recycle Bin."
                                },
                            )
                        }
                    }
                }

                KeyAccessTab.RECYCLE_BIN -> {
                    if (recycleItems.isEmpty()) {
                        EmptyState("The Keys, Slots & Access Recycle Bin is empty.")
                    } else {
                        recycleItems.forEach { item ->
                            RecycleItemCard(
                                item = item,
                                onRestore = {
                                    when (item.kind) {
                                        RecycleItemKind.KEY -> keys = keys.map {
                                            if (it.id == item.id) it.copy(lifecycle = restoreMetadata(it.lifecycle)) else it
                                        }

                                        RecycleItemKind.SLOT -> slots = slots.map {
                                            if (it.id == item.id) it.copy(lifecycle = restoreMetadata(it.lifecycle)) else it
                                        }

                                        RecycleItemKind.GRANT -> grants = grants.map {
                                            if (it.id == item.id) it.copy(lifecycle = restoreMetadata(it.lifecycle)) else it
                                        }
                                    }
                                    notice = "${item.title} restored."
                                },
                                onPurge = { pendingPurge = item },
                            )
                        }
                    }
                }
            }
        }
    }

    keyEditor?.let { editor ->
        KeyEditorDialog(
            editor = editor,
            sites = sites,
            activeKeys = activeKeys,
            onDismiss = { keyEditor = null },
            onSave = { saved ->
                val timestamp = now()
                val existing = keys.firstOrNull { it.id == saved.id }
                val updated = ManagedKey(
                    id = saved.id ?: "key_local_${timestamp}",
                    siteId = saved.siteId,
                    displayName = saved.displayName.trim(),
                    fobEnrollmentReference = existing?.fobEnrollmentReference,
                    lifecycle = existing?.lifecycle?.copy(
                        updatedAtEpochMillis = timestamp,
                    ) ?: LifecycleMetadata(
                        createdAtEpochMillis = timestamp,
                        updatedAtEpochMillis = timestamp,
                    ),
                )
                keys = if (saved.id == null) keys + updated else keys.map {
                    if (it.id == updated.id) updated else it
                }
                keyEditor = null
                notice = "${updated.displayName} saved locally. A physical fob can only be enrolled on the protected Android Terminal."
            },
        )
    }

    slotEditor?.let { editor ->
        SlotEditorDialog(
            editor = editor,
            terminals = terminals,
            activeKeys = activeKeys,
            activeSlots = activeSlots,
            onDismiss = { slotEditor = null },
            onSave = { saved ->
                val timestamp = now()
                val existing = slots.firstOrNull { it.id == saved.id }
                val updated = KeySlot(
                    id = saved.id ?: "slot_local_${timestamp}",
                    terminalId = saved.terminalId,
                    nodeAddress = saved.nodeAddress.trim().toInt(),
                    managedKeyId = saved.keyId.trim().ifBlank { null },
                    lifecycle = existing?.lifecycle?.copy(updatedAtEpochMillis = timestamp)
                        ?: LifecycleMetadata(
                            createdAtEpochMillis = timestamp,
                            updatedAtEpochMillis = timestamp,
                        ),
                )
                slots = if (saved.id == null) slots + updated else slots.map {
                    if (it.id == updated.id) updated else it
                }
                slotEditor = null
                notice = "Node ${updated.nodeAddress} saved locally. No cabinet command was sent."
            },
        )
    }

    grantEditor?.let { editor ->
        AccessGrantEditorDialog(
            editor = editor,
            users = activeUsers,
            sites = sites,
            activeKeys = activeKeys,
            onDismiss = { grantEditor = null },
            onSave = { saved ->
                val timestamp = now()
                val existing = grants.firstOrNull { it.id == saved.id }
                val updated = AccessGrant(
                    id = saved.id ?: "grant_local_${timestamp}",
                    userId = saved.userId,
                    siteId = saved.siteId,
                    keyIds = saved.keyIds,
                    validFromEpochMillis = KeySlotAccessPolicy.validFromEpochMillis(saved.validFrom),
                    validUntilEpochMillis = KeySlotAccessPolicy.validUntilEpochMillis(saved.validUntil),
                    lifecycle = existing?.lifecycle?.copy(updatedAtEpochMillis = timestamp)
                        ?: LifecycleMetadata(
                            createdAtEpochMillis = timestamp,
                            updatedAtEpochMillis = timestamp,
                        ),
                )
                grants = if (saved.id == null) grants + updated else grants.map {
                    if (it.id == updated.id) updated else it
                }
                grantEditor = null
                notice = "Exact access grant saved locally. Terminal authorization and sync will use the backend policy later."
            },
        )
    }

    pendingPurge?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingPurge = null },
            title = { Text("Permanently clear ${item.title}?") },
            text = {
                Text(
                    "This local demo record cannot be restored after clearing. " +
                            "Production purge remains Super Admin-only and audit history is retained.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (item.kind) {
                            RecycleItemKind.KEY -> keys = keys.map {
                                if (it.id == item.id) it.copy(lifecycle = purgeMetadata(it.lifecycle)) else it
                            }

                            RecycleItemKind.SLOT -> slots = slots.map {
                                if (it.id == item.id) it.copy(lifecycle = purgeMetadata(it.lifecycle)) else it
                            }

                            RecycleItemKind.GRANT -> grants = grants.map {
                                if (it.id == item.id) it.copy(lifecycle = purgeMetadata(it.lifecycle)) else it
                            }
                        }
                        notice = "${item.title} permanently cleared from the local demo Recycle Bin."
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
private fun KeyRecordCard(
    key: ManagedKey,
    siteLabel: String,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(key.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(siteLabel)
            Text(
                text = if (key.fobEnrollmentReference.isNullOrBlank()) {
                    "Physical fob: not enrolled"
                } else {
                    "Physical fob: enrolled (identifier protected)"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onArchive) { Text("Move to Recycle Bin") }
        }
    }
}

@Composable
private fun SlotRecordCard(
    slot: KeySlot,
    terminalLabel: String,
    keyLabel: String,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("Node ${slot.nodeAddress}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(terminalLabel)
            Text("Assigned key: ${keyLabel}", style = MaterialTheme.typography.bodyMedium)
            Text("Protocol key node only; no hidden -1 address conversion.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onEdit) { Text("Edit slot") }
            TextButton(onClick = onArchive) { Text("Move to Recycle Bin") }
        }
    }
}

@Composable
private fun GrantRecordCard(
    grant: AccessGrant,
    userLabel: String,
    siteLabel: String,
    keyLabels: List<String>,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(userLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Site: ${siteLabel}")
            Text("Exact keys: ${keyLabels.joinToString()}")
            Text(
                "Validity: ${formatIsoDate(grant.validFromEpochMillis) ?: "Any date"} to " +
                        "${formatIsoDate(grant.validUntilEpochMillis) ?: "No expiry"}",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onEdit) { Text("Edit access grant") }
            TextButton(onClick = onArchive) { Text("Move to Recycle Bin") }
        }
    }
}

@Composable
private fun RecycleItemCard(
    item: RecycleItem,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(item.detail, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onRestore) { Text("Restore") }
            TextButton(onClick = onPurge) { Text("Clear permanently") }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun KeyEditorDialog(
    editor: KeyEditorState,
    sites: List<ManagedSiteOption>,
    activeKeys: List<ManagedKey>,
    onDismiss: () -> Unit,
    onSave: (KeyEditorState) -> Unit,
) {
    var form by remember(editor.id) { mutableStateOf(editor) }
    val issues = KeySlotAccessPolicy.validateKey(
        draft = KeyDraft(form.displayName, form.siteId),
        knownSiteIds = sites.mapTo(linkedSetOf()) { it.id },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.id == null) "Register key" else "Edit key") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Website creates the logical key record only. Physical fob enrolment is a protected " +
                            "Terminal-only action, so Website never accepts or displays a raw fob UID.",
                )
                OutlinedTextField(
                    value = form.displayName,
                    onValueChange = { form = form.copy(displayName = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Key name") },
                    isError = KeySlotAccessPolicy.errorFor(issues, KeyAccessField.KEY_NAME) != null,
                )
                FieldError(KeySlotAccessPolicy.errorFor(issues, KeyAccessField.KEY_NAME))
                SelectionField(
                    label = "Site",
                    selectedId = form.siteId,
                    choices = sites.map { SelectionChoice(it.id, it.label) },
                    error = KeySlotAccessPolicy.errorFor(issues, KeyAccessField.KEY_SITE),
                    onSelected = { form = form.copy(siteId = it) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (issues.isEmpty()) onSave(form) },
                enabled = issues.isEmpty(),
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SlotEditorDialog(
    editor: SlotEditorState,
    terminals: List<ManagedTerminalOption>,
    activeKeys: List<ManagedKey>,
    activeSlots: List<KeySlot>,
    onDismiss: () -> Unit,
    onSave: (SlotEditorState) -> Unit,
) {
    var form by remember(editor.id) { mutableStateOf(editor) }
    val selectedTerminal = terminals.firstOrNull { it.id == form.terminalId }
    val availableKeys = activeKeys.filter { it.siteId == selectedTerminal?.siteId }
    val issues = KeySlotAccessPolicy.validateSlot(
        draft = KeySlotDraft(form.terminalId, form.nodeAddress, form.keyId),
        terminals = terminals,
        activeKeys = activeKeys,
        activeSlots = activeSlots,
        editingSlotId = form.id,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.id == null) "Register cabinet slot" else "Edit cabinet slot") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("This only saves a physical mapping. It does not turn on a key-node light, release a peg or open the door.")
                SelectionField(
                    label = "Terminal",
                    selectedId = form.terminalId,
                    choices = terminals.map {
                        SelectionChoice(it.id, "${it.label} · ${it.configuredSlotCount} key nodes")
                    },
                    error = KeySlotAccessPolicy.errorFor(issues, KeyAccessField.SLOT_TERMINAL),
                    onSelected = { form = form.copy(terminalId = it, keyId = "") },
                )
                OutlinedTextField(
                    value = form.nodeAddress,
                    onValueChange = { form = form.copy(nodeAddress = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Key node address") },
                    placeholder = { Text("1 to ${selectedTerminal?.configuredSlotCount ?: "capacity"}") },
                    isError = KeySlotAccessPolicy.errorFor(issues, KeyAccessField.SLOT_NODE_ADDRESS) != null,
                    singleLine = true,
                )
                FieldError(KeySlotAccessPolicy.errorFor(issues, KeyAccessField.SLOT_NODE_ADDRESS))
                SelectionField(
                    label = "Assigned key",
                    selectedId = form.keyId,
                    choices = listOf(SelectionChoice("", "No key assigned")) + availableKeys.map {
                        SelectionChoice(it.id, it.displayName)
                    },
                    error = KeySlotAccessPolicy.errorFor(issues, KeyAccessField.SLOT_KEY),
                    onSelected = { form = form.copy(keyId = it) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (issues.isEmpty()) onSave(form) },
                enabled = issues.isEmpty(),
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AccessGrantEditorDialog(
    editor: GrantEditorState,
    users: List<AdminUser>,
    sites: List<ManagedSiteOption>,
    activeKeys: List<ManagedKey>,
    onDismiss: () -> Unit,
    onSave: (GrantEditorState) -> Unit,
) {
    var form by remember(editor.id) { mutableStateOf(editor) }
    val selectedUser = users.firstOrNull { it.id == form.userId }
    val allowedSites = if (selectedUser?.role == UserRole.SUPER_ADMIN) {
        sites
    } else {
        sites.filter { it.id in selectedUser?.assignedSiteIds.orEmpty() }
    }
    val availableKeys = activeKeys.filter { it.siteId == form.siteId }
    val issues = KeySlotAccessPolicy.validateAccessGrant(
        draft = AccessGrantDraft(
            userId = form.userId,
            siteId = form.siteId,
            keyIds = form.keyIds,
            validFromDateText = form.validFrom,
            validUntilDateText = form.validUntil,
        ),
        activeUsers = users,
        knownSiteIds = sites.mapTo(linkedSetOf()) { it.id },
        activeKeys = activeKeys,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.id == null) "Create exact access grant" else "Edit exact access grant") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Grant only the selected physical keys. The backend will enforce the same policy during offline sync.")
                SelectionField(
                    label = "User",
                    selectedId = form.userId,
                    choices = users.map { SelectionChoice(it.id, "${it.displayName} · ${it.role.name}") },
                    error = KeySlotAccessPolicy.errorFor(issues, KeyAccessField.GRANT_USER),
                    onSelected = { userId ->
                        val user = users.firstOrNull { it.id == userId }
                        val defaultSite = if (user?.role == UserRole.SUPER_ADMIN) {
                            sites.firstOrNull()?.id.orEmpty()
                        } else {
                            user?.assignedSiteIds?.firstOrNull().orEmpty()
                        }
                        form = form.copy(userId = userId, siteId = defaultSite, keyIds = emptySet())
                    },
                )
                SelectionField(
                    label = "Site",
                    selectedId = form.siteId,
                    choices = allowedSites.map { SelectionChoice(it.id, it.label) },
                    error = KeySlotAccessPolicy.errorFor(issues, KeyAccessField.GRANT_SITE),
                    onSelected = { form = form.copy(siteId = it, keyIds = emptySet()) },
                )
                Text("Exact keys", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (availableKeys.isEmpty()) {
                    Text("No active keys are registered for this site.", style = MaterialTheme.typography.bodySmall)
                }
                availableKeys.forEach { key ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = key.id in form.keyIds,
                            onCheckedChange = { checked ->
                                form = form.copy(
                                    keyIds = if (checked) form.keyIds + key.id else form.keyIds - key.id,
                                )
                            },
                        )
                        Text(key.displayName)
                    }
                }
                FieldError(KeySlotAccessPolicy.errorFor(issues, KeyAccessField.GRANT_KEYS))
                OutlinedTextField(
                    value = form.validFrom,
                    onValueChange = { form = form.copy(validFrom = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Valid from (optional YYYY-MM-DD)") },
                    isError = KeySlotAccessPolicy.errorFor(issues, KeyAccessField.VALID_FROM) != null,
                    singleLine = true,
                )
                FieldError(KeySlotAccessPolicy.errorFor(issues, KeyAccessField.VALID_FROM))
                OutlinedTextField(
                    value = form.validUntil,
                    onValueChange = { form = form.copy(validUntil = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Valid until (optional YYYY-MM-DD)") },
                    isError = KeySlotAccessPolicy.errorFor(issues, KeyAccessField.VALID_UNTIL) != null,
                    singleLine = true,
                )
                FieldError(KeySlotAccessPolicy.errorFor(issues, KeyAccessField.VALID_UNTIL))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (issues.isEmpty()) onSave(form) },
                enabled = issues.isEmpty(),
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SelectionField(
    label: String,
    selectedId: String,
    choices: List<SelectionChoice>,
    error: String?,
    onSelected: (String) -> Unit,
) {
    var expanded by remember(label, selectedId) { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.id == selectedId }?.label ?: "Select $label"

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selectedLabel)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    onClick = {
                        expanded = false
                        onSelected(choice.id)
                    },
                )
            }
        }
        FieldError(error)
    }
}

@Composable
private fun FieldError(message: String?) {
    if (message != null) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun siteLabel(siteId: String, sites: List<ManagedSiteOption>): String =
    sites.firstOrNull { it.id == siteId }?.label ?: "Unknown site"

private fun terminalLabel(terminalId: String, terminals: List<ManagedTerminalOption>): String =
    terminals.firstOrNull { it.id == terminalId }?.label ?: "Unknown terminal"

private fun keyLabel(keyId: String?, keys: List<ManagedKey>): String =
    if (keyId.isNullOrBlank()) "No key assigned" else keys.firstOrNull { it.id == keyId }?.displayName ?: "Unavailable key"

private fun userLabel(userId: String, users: List<AdminUser>): String =
    users.firstOrNull { it.id == userId }?.displayName ?: "Unknown user"

private fun formatIsoDate(epochMillis: Long?): String? = epochMillis?.let {
    Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString()
}