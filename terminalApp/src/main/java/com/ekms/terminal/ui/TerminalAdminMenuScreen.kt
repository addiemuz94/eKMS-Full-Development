package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.shared.api.SiteDto
import com.ekms.shared.protocol.KeyCabinetLink.Companion.MAX_KEY_NODE_ADDRESS
import com.ekms.terminal.data.TerminalCabinetSettings
import com.ekms.terminal.data.TerminalUserRole
import com.ekms.terminal.hardware.readEthernetMacAddress
import com.ekms.terminal.ui.theme.readout

/**
 * Phase 7 (17-item permission matrix): per-field Edit / View-only / Hidden mode, resolved once
 * per item from the signed-in role via [adminMenuFieldMode] — this is the single source of truth
 * for the Admin Menu's role gating, rather than `if (role == ...)` scattered through the layout
 * below. Every field on this screen is one of these three; there is no fourth state.
 */
enum class AdminFieldMode { EDIT, VIEW, HIDDEN }

/** Items 1-12 of the confirmed matrix, in the same order the screen renders them. Item 7 (MAC
 * address) has no real "edit" concept — it's always a read-only hardware value — but is still
 * modeled through this same enum for consistency: [AdminFieldMode.EDIT] and [AdminFieldMode.VIEW]
 * render identically for it (the existing read-only card), only [AdminFieldMode.HIDDEN] changes
 * anything. */
private enum class AdminMenuField {
    TERMINAL_NAME,
    KEY_CABINET_ID,
    PASSWORD_CHANGE,
    SERVER_ADDRESS,
    ACTIVATION_CODE,
    KEY_NODE_SETTING,
    MAC_ADDRESS,
    KEY_RETURN_CERTIFICATION,
    RETURN_VIDEO,
    RETRIEVAL_VIDEO,
    TAKE_WARNING_TIME,
    DOOR_CLOSE_WARNING_TIME,
}

/**
 * Super Admin: every item is [AdminFieldMode.EDIT], unchanged from before Phase 7. Regional
 * Admin (the only other role [isAdminTier] admits to this screen): server address, activation
 * code, MAC address, and password change are [AdminFieldMode.HIDDEN] per the matrix — not
 * greyed out, not shown-disabled, genuinely absent; everything else is
 * [AdminFieldMode.VIEW] (current value shown, no input control, no save action).
 */
private fun adminMenuFieldMode(role: TerminalUserRole, field: AdminMenuField): AdminFieldMode {
    if (role == TerminalUserRole.SUPER_ADMIN) return AdminFieldMode.EDIT
    return when (field) {
        AdminMenuField.PASSWORD_CHANGE,
        AdminMenuField.SERVER_ADDRESS,
        AdminMenuField.ACTIVATION_CODE,
        AdminMenuField.MAC_ADDRESS,
        -> AdminFieldMode.HIDDEN

        else -> AdminFieldMode.VIEW
    }
}

/**
 * Smart Key Cabinet User Manual V2.1, Section 4 — Admin Menu.
 *
 * Reachable only after admin login (see the "Admin Menu" button on the
 * Super Admin dashboard), never from the ordinary operator flow. All ten
 * manual items are represented, using the manual's own terminology for
 * every label rather than a paraphrase:
 * 1. Name of key cabinet · 2. Key Cabinet ID · 3. Modify administrator
 * password · 4. Set server address · 5. Activation code setup · 6. Key
 * node setting · 7. Ethernet MAC address · 8. Key Return Certification ·
 * 9. Return key video · 10. Key retrieval video.
 *
 * Items 1, 2, 4, 5, 6, 8, 9, 10 are one form saved together. Item 3 opens
 * the existing password-change screen rather than duplicating it. Item 7
 * is read-only and is not part of the saved settings record.
 *
 * Take Warning Time and Door-Close Warning Time are documented production
 * enhancements beyond the manual (CLAUDE.md "Terminal App UX Baseline
 * (Production)" §1/§2) — the Key Take Flow's and Key Return Flow's own
 * countdowns, in seconds, before their respective "please close the door"
 * voice lines. Two genuinely separate settings, not one shared value.
 * Saved alongside items 8-10 using the same settings record, not a
 * separate config path.
 *
 * Phase 7: [role] drives per-item [AdminFieldMode] via [adminMenuFieldMode]. The "Backend
 * synchronization" card (Bootstrap/Push/Read/Download) isn't one of the matrix's 17 numbered
 * items. Per the follow-up decision, Regional Admin gets partial visibility on this one card —
 * Bootstrap and Download (read/pull-type operations) stay usable; Push and Read (write/mutating,
 * or otherwise not appropriate for this role) are hidden. Super Admin sees all four, unchanged.
 *
 * Phase 9D (visual/theme only — [adminMenuFieldMode]'s resolution and the Backend Sync role
 * split above are both untouched): every card here (read-only fields, the toggle rows, MAC
 * address, Backend Sync) now uses [SoftCard] instead of a plain M3 `Card`, matching the rest of
 * the sweep. Deliberately **not** rebuilt on [SoftScanTile]: that component is a tappable
 * selection tile (icon badge, `onClick`, `listening`/`selected` pulse states) meant for choosing
 * between a handful of options side by side (Login method, Key Menu) — this screen is a single
 * scrolling settings *form*, where every row is either a labeled input or a plain value, never a
 * "pick one of these" choice. Forcing tile semantics onto form fields would be a mismatch, so
 * the existing [AdminMenuStringField]/[AdminMenuReadOnlyField]/[AdminMenuToggle] row shapes were
 * kept and just re-skinned. The "Save Admin Menu settings" button is now [IconActionButton]
 * ([ActionButtonType.ACCEPT]); Bootstrap/Push/Read/Download and "Modify administrator password"
 * stay plain `OutlinedButton` — already theme-correct via M3 defaults, and none of them is an
 * ACCEPT/CANCEL/ADD-shaped action (they're standalone triggers/navigation, not a pending edit to
 * confirm or dismiss), so `IconActionButton` wasn't forced onto them.
 */
@Composable
fun TerminalAdminMenuScreen(
    padding: PaddingValues,
    role: TerminalUserRole,
    /** Item 14 — Regional Admin's own assigned sites, view-only, shown near the terminal-identity
     * fields (items 1/2) rather than as a dedicated screen, per the spec's "can be small" note.
     * Empty/unused for Super Admin, who isn't site-restricted. */
    assignedSiteIds: Set<String> = emptySet(),
    /** Resolves [assignedSiteIds] to display names via `GET /v1/admin/sites` (now on the
     * Regional Admin allowlist, server-side scoped to their own sites — see `sites.js`). Only
     * called for a non-Super-Admin role; falls back to the raw id per site on failure rather
     * than blocking the rest of the screen. */
    onLoadSites: suspend () -> List<SiteDto> = { emptyList() },
    settings: TerminalCabinetSettings,
    highestRegisteredNodeAddress: Int?,
    notice: String?,
    syncBusy: Boolean = false,
    pendingOutboxCount: Int = 0,
    onBack: () -> Unit,
    onSave: (TerminalCabinetSettings) -> Unit,
    onOpenPasswordChange: () -> Unit,
    onBootstrap: () -> Unit = {},
    onPush: () -> Unit = {},
    onRead: () -> Unit = {},
    onDownload: () -> Unit = {},
) {
    fun modeOf(field: AdminMenuField) = adminMenuFieldMode(role, field)

    var siteNamesById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(role, assignedSiteIds) {
        if (role != TerminalUserRole.SUPER_ADMIN && assignedSiteIds.isNotEmpty()) {
            siteNamesById = runCatching { onLoadSites() }
                .getOrDefault(emptyList())
                .associate { it.id to it.name }
        }
    }

    var cabinetName by remember(settings) { mutableStateOf(settings.cabinetName) }
    var cabinetId by remember(settings) { mutableStateOf(settings.cabinetId) }
    var serverAddress by remember(settings) { mutableStateOf(settings.serverAddress) }
    var activationCode by remember(settings) { mutableStateOf(settings.activationCode) }
    var keyNodeCountText by remember(settings) { mutableStateOf(settings.configuredKeyNodeCount.toString()) }
    var keyReturnCertificationEnabled by remember(settings) { mutableStateOf(settings.keyReturnCertificationEnabled) }
    var returnKeyVideoEnabled by remember(settings) { mutableStateOf(settings.returnKeyVideoEnabled) }
    var keyRetrievalVideoEnabled by remember(settings) { mutableStateOf(settings.keyRetrievalVideoEnabled) }
    var takeWarningTimeText by remember(settings) { mutableStateOf(settings.takeWarningTimeSeconds.toString()) }
    var doorCloseWarningTimeText by remember(settings) { mutableStateOf(settings.doorCloseWarningTimeSeconds.toString()) }
    val macAddress = remember { readEthernetMacAddress() ?: "Unavailable" }

    val keyNodeCount = keyNodeCountText.trim().toIntOrNull()
    val keyNodeCountError = when {
        keyNodeCount == null -> "Key node setting must be a number from 1 to $MAX_KEY_NODE_ADDRESS."
        keyNodeCount !in 1..MAX_KEY_NODE_ADDRESS -> "Key node setting must be a number from 1 to $MAX_KEY_NODE_ADDRESS."
        highestRegisteredNodeAddress != null && keyNodeCount < highestRegisteredNodeAddress ->
            "A key is already registered at node $highestRegisteredNodeAddress. Remove it before lowering the key node setting below that."
        else -> null
    }

    val takeWarningTimeSeconds = takeWarningTimeText.trim().toIntOrNull()
    val takeWarningTimeError = when {
        takeWarningTimeSeconds == null -> "Take Warning Time must be a number from 1 to $MAX_TAKE_WARNING_TIME_SECONDS."
        takeWarningTimeSeconds !in 1..MAX_TAKE_WARNING_TIME_SECONDS ->
            "Take Warning Time must be a number from 1 to $MAX_TAKE_WARNING_TIME_SECONDS."
        else -> null
    }

    val doorCloseWarningTimeSeconds = doorCloseWarningTimeText.trim().toIntOrNull()
    val doorCloseWarningTimeError = when {
        doorCloseWarningTimeSeconds == null ->
            "Door-Close Warning Time must be a number from 1 to $MAX_DOOR_CLOSE_WARNING_TIME_SECONDS."
        doorCloseWarningTimeSeconds !in 1..MAX_DOOR_CLOSE_WARNING_TIME_SECONDS ->
            "Door-Close Warning Time must be a number from 1 to $MAX_DOOR_CLOSE_WARNING_TIME_SECONDS."
        else -> null
    }

    TerminalPage(padding) {
        BackButton(onBack)
        HeaderCard(
            title = "Admin Menu",
            description = "Terminal identity, network and security settings.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }

        AdminMenuStringField(
            mode = modeOf(AdminMenuField.TERMINAL_NAME),
            label = "Name of key cabinet",
            value = cabinetName,
            onValueChange = { cabinetName = it },
        )
        AdminMenuStringField(
            mode = modeOf(AdminMenuField.KEY_CABINET_ID),
            label = "Key Cabinet ID",
            value = cabinetId,
            onValueChange = { cabinetId = it },
            supportingText = "Use the backend terminal UUID for sync bootstrap/push/read/download.",
        )
        if (role != TerminalUserRole.SUPER_ADMIN) {
            AdminMenuReadOnlyField(
                label = "Your assigned site(s)",
                value = if (assignedSiteIds.isEmpty()) {
                    "None assigned"
                } else {
                    assignedSiteIds.joinToString { id -> siteNamesById[id] ?: id }
                },
            )
        }
        if (modeOf(AdminMenuField.PASSWORD_CHANGE) != AdminFieldMode.HIDDEN) {
            OutlinedButton(onClick = onOpenPasswordChange, modifier = Modifier.fillMaxWidth()) {
                Text("Modify administrator password")
            }
        }
        if (modeOf(AdminMenuField.SERVER_ADDRESS) != AdminFieldMode.HIDDEN) {
            AdminMenuStringField(
                mode = modeOf(AdminMenuField.SERVER_ADDRESS),
                label = "Set server address",
                value = serverAddress,
                onValueChange = { serverAddress = it },
                supportingText = "Production: https://kms-cvt.com — LAN: http://192.168.x.x:3001 (not localhost on a device).",
            )
        }
        if (modeOf(AdminMenuField.ACTIVATION_CODE) != AdminFieldMode.HIDDEN) {
            AdminMenuStringField(
                mode = modeOf(AdminMenuField.ACTIVATION_CODE),
                label = "Activation code setup",
                value = activationCode,
                onValueChange = { activationCode = it },
            )
        }
        if (modeOf(AdminMenuField.KEY_NODE_SETTING) == AdminFieldMode.EDIT) {
            OutlinedTextField(
                value = keyNodeCountText,
                onValueChange = { keyNodeCountText = it.filter { character -> character.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Key node setting") },
                singleLine = true,
                isError = keyNodeCountError != null,
                supportingText = { Text(keyNodeCountError ?: "Configured key-node capacity for this cabinet.") },
            )
        } else {
            AdminMenuReadOnlyField(label = "Key node setting", value = keyNodeCountText)
        }
        if (modeOf(AdminMenuField.MAC_ADDRESS) != AdminFieldMode.HIDDEN) {
            SoftCard(contentPadding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ethernet MAC address", fontWeight = FontWeight.SemiBold)
                    Text(macAddress, style = MaterialTheme.typography.bodyMedium.readout())
                }
            }
        }
        AdminMenuToggle(
            mode = modeOf(AdminMenuField.KEY_RETURN_CERTIFICATION),
            title = "Key Return Certification",
            description = "Require login authentication before the key return door opens.",
            checked = keyReturnCertificationEnabled,
            onCheckedChange = { keyReturnCertificationEnabled = it },
        )
        AdminMenuToggle(
            mode = modeOf(AdminMenuField.RETURN_VIDEO),
            title = "Return key video",
            description = "Record background video during key return.",
            checked = returnKeyVideoEnabled,
            onCheckedChange = { returnKeyVideoEnabled = it },
        )
        AdminMenuToggle(
            mode = modeOf(AdminMenuField.RETRIEVAL_VIDEO),
            title = "Key retrieval video",
            description = "Record background video during key retrieval.",
            checked = keyRetrievalVideoEnabled,
            onCheckedChange = { keyRetrievalVideoEnabled = it },
        )
        if (modeOf(AdminMenuField.TAKE_WARNING_TIME) == AdminFieldMode.EDIT) {
            OutlinedTextField(
                value = takeWarningTimeText,
                onValueChange = { takeWarningTimeText = it.filter { character -> character.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Take Warning Time (seconds)") },
                singleLine = true,
                isError = takeWarningTimeError != null,
                supportingText = {
                    Text(
                        takeWarningTimeError
                            ?: "Key Take Flow: how long after removal before the \"please close the door\" voice line.",
                    )
                },
            )
        } else {
            AdminMenuReadOnlyField(label = "Take Warning Time (seconds)", value = takeWarningTimeText)
        }
        if (modeOf(AdminMenuField.DOOR_CLOSE_WARNING_TIME) == AdminFieldMode.EDIT) {
            OutlinedTextField(
                value = doorCloseWarningTimeText,
                onValueChange = { doorCloseWarningTimeText = it.filter { character -> character.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Door-Close Warning Time (seconds)") },
                singleLine = true,
                isError = doorCloseWarningTimeError != null,
                supportingText = {
                    Text(
                        doorCloseWarningTimeError
                            ?: "Key Return Flow: how long after insertion before the \"please close the door\" voice line.",
                    )
                },
            )
        } else {
            AdminMenuReadOnlyField(label = "Door-Close Warning Time (seconds)", value = doorCloseWarningTimeText)
        }

        // Backend synchronization: partial visibility for Regional Admin, per the follow-up
        // decision — Bootstrap/Download are read/pull-type operations and stay usable; Push
        // (writes local changes to the server) and Read are hidden, consistent with "never
        // editable" for Regional Admin at the terminal. Super Admin sees all four, unchanged.
        // Phase 9D: SoftCard instead of plain Card (visual only) — the `if (role ==
        // SUPER_ADMIN)` guard around Push/Read is untouched, still a genuine conditional skip,
        // not a disabled/greyed pair of buttons. Bootstrap/Download/Push/Read stay
        // OutlinedButton — already theme-correct via M3 defaults (no color override existed to
        // fix), and none of the four is an ACCEPT/CANCEL/ADD-shaped action, so IconActionButton
        // was deliberately not forced onto them (task scope: only "apply theme tokens" here).
        SoftCard(contentPadding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backend synchronization", fontWeight = FontWeight.SemiBold)
                Text(
                    "Pending offline changes: $pendingOutboxCount. Sign in with a server account, then run sync.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onBootstrap, enabled = !syncBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Bootstrap from server")
                }
                if (role == TerminalUserRole.SUPER_ADMIN) {
                    OutlinedButton(onClick = onPush, enabled = !syncBusy, modifier = Modifier.fillMaxWidth()) {
                        Text("Push offline changes")
                    }
                    OutlinedButton(onClick = onRead, enabled = !syncBusy, modifier = Modifier.fillMaxWidth()) {
                        Text("Read request")
                    }
                }
                OutlinedButton(onClick = onDownload, enabled = !syncBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Download configuration")
                }
            }
        }

        if (role == TerminalUserRole.SUPER_ADMIN) {
            // Phase 9D: IconActionButton(ACCEPT) in place of the old plain Button — the same
            // "confirm this pending settings change" case Office Hours' Save button fit
            // (Phase 9C). No Cancel button existed before and none is added now — Save + the
            // shared page-level Back button is this screen's whole action set, same as Office
            // Hours.
            IconActionButton(
                type = ActionButtonType.ACCEPT,
                label = "Save Admin Menu settings",
                onClick = {
                    onSave(
                        TerminalCabinetSettings(
                            cabinetName = cabinetName,
                            cabinetId = cabinetId,
                            serverAddress = serverAddress,
                            activationCode = activationCode,
                            configuredKeyNodeCount = requireNotNull(keyNodeCount),
                            keyReturnCertificationEnabled = keyReturnCertificationEnabled,
                            returnKeyVideoEnabled = returnKeyVideoEnabled,
                            keyRetrievalVideoEnabled = keyRetrievalVideoEnabled,
                            takeWarningTimeSeconds = requireNotNull(takeWarningTimeSeconds),
                            doorCloseWarningTimeSeconds = requireNotNull(doorCloseWarningTimeSeconds),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = keyNodeCountError == null && takeWarningTimeError == null &&
                    doorCloseWarningTimeError == null && !syncBusy,
            )
        }
    }
}

private const val MAX_TAKE_WARNING_TIME_SECONDS = 300
private const val MAX_DOOR_CLOSE_WARNING_TIME_SECONDS = 300

@Composable
private fun AdminMenuStringField(
    mode: AdminFieldMode,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supportingText: String? = null,
) {
    if (mode == AdminFieldMode.EDIT) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            supportingText = supportingText?.let { text -> { Text(text) } },
        )
    } else {
        AdminMenuReadOnlyField(label = label, value = value)
    }
}

/**
 * [AdminFieldMode.VIEW] rendering shared by every item — current value, no input control.
 * Phase 9D: `SoftCard` instead of a plain M3 `Card` — same row-visual-language swap as every
 * other card on this screen this pass, no functional change (this composable was already only
 * ever called from a non-HIDDEN branch by every caller).
 */
@Composable
private fun AdminMenuReadOnlyField(label: String, value: String) {
    SoftCard(contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                value.ifBlank { "Not set" },
                style = MaterialTheme.typography.bodyMedium.readout(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Phase 9D: `SoftCard` instead of plain `Card` — the `if (mode == HIDDEN) return` guard above
 * everything else is untouched, still a genuine early-return with zero composable emission. */
@Composable
private fun AdminMenuToggle(
    mode: AdminFieldMode,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (mode == AdminFieldMode.HIDDEN) return
    SoftCard(contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
            if (mode == AdminFieldMode.EDIT) {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            } else {
                Text(
                    if (checked) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodyMedium.readout(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
