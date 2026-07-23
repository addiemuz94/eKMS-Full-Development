package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.shared.protocol.KeyCabinetLink.Companion.MAX_KEY_NODE_ADDRESS
import com.ekms.terminal.data.TerminalCabinetSettings
import com.ekms.terminal.hardware.readEthernetMacAddress

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
 */
@Composable
fun TerminalAdminMenuScreen(
    padding: PaddingValues,
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
    var cabinetName by remember(settings) { mutableStateOf(settings.cabinetName) }
    var cabinetId by remember(settings) { mutableStateOf(settings.cabinetId) }
    var serverAddress by remember(settings) { mutableStateOf(settings.serverAddress) }
    var activationCode by remember(settings) { mutableStateOf(settings.activationCode) }
    var keyNodeCountText by remember(settings) { mutableStateOf(settings.configuredKeyNodeCount.toString()) }
    var keyReturnCertificationEnabled by remember(settings) { mutableStateOf(settings.keyReturnCertificationEnabled) }
    var returnKeyVideoEnabled by remember(settings) { mutableStateOf(settings.returnKeyVideoEnabled) }
    var keyRetrievalVideoEnabled by remember(settings) { mutableStateOf(settings.keyRetrievalVideoEnabled) }
    val macAddress = remember { readEthernetMacAddress() ?: "Unavailable" }

    val keyNodeCount = keyNodeCountText.trim().toIntOrNull()
    val keyNodeCountError = when {
        keyNodeCount == null -> "Key node setting must be a number from 1 to $MAX_KEY_NODE_ADDRESS."
        keyNodeCount !in 1..MAX_KEY_NODE_ADDRESS -> "Key node setting must be a number from 1 to $MAX_KEY_NODE_ADDRESS."
        highestRegisteredNodeAddress != null && keyNodeCount < highestRegisteredNodeAddress ->
            "A key is already registered at node $highestRegisteredNodeAddress. Remove it before lowering the key node setting below that."
        else -> null
    }

    TerminalPage(padding) {
        BackButton(onBack)
        HeaderCard(
            title = "Admin Menu",
            description = "Terminal identity, network and security settings.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }

        OutlinedTextField(
            value = cabinetName,
            onValueChange = { cabinetName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name of key cabinet") },
            singleLine = true,
        )
        OutlinedTextField(
            value = cabinetId,
            onValueChange = { cabinetId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Key Cabinet ID") },
            singleLine = true,
            supportingText = {
                Text("Use the backend terminal UUID for sync bootstrap/push/read/download.")
            },
        )
        OutlinedButton(onClick = onOpenPasswordChange, modifier = Modifier.fillMaxWidth()) {
            Text("Modify administrator password")
        }
        OutlinedTextField(
            value = serverAddress,
            onValueChange = { serverAddress = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Set server address") },
            singleLine = true,
            supportingText = {
                Text("LAN backend URL, e.g. http://192.168.1.10:3000 (not localhost on a physical device).")
            },
        )
        OutlinedTextField(
            value = activationCode,
            onValueChange = { activationCode = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Activation code setup") },
            singleLine = true,
        )
        OutlinedTextField(
            value = keyNodeCountText,
            onValueChange = { keyNodeCountText = it.filter { character -> character.isDigit() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Key node setting") },
            singleLine = true,
            isError = keyNodeCountError != null,
            supportingText = { Text(keyNodeCountError ?: "Configured key-node capacity for this cabinet.") },
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Ethernet MAC address", fontWeight = FontWeight.SemiBold)
                Text(macAddress, style = MaterialTheme.typography.bodyMedium)
            }
        }
        AdminMenuToggle(
            title = "Key Return Certification",
            description = "Require login authentication before the key return door opens.",
            checked = keyReturnCertificationEnabled,
            onCheckedChange = { keyReturnCertificationEnabled = it },
        )
        AdminMenuToggle(
            title = "Return key video",
            description = "Record background video during key return.",
            checked = returnKeyVideoEnabled,
            onCheckedChange = { returnKeyVideoEnabled = it },
        )
        AdminMenuToggle(
            title = "Key retrieval video",
            description = "Record background video during key retrieval.",
            checked = keyRetrievalVideoEnabled,
            onCheckedChange = { keyRetrievalVideoEnabled = it },
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Backend synchronization", fontWeight = FontWeight.SemiBold)
                Text(
                    "Pending offline changes: $pendingOutboxCount. Sign in with a server account, then run sync.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onBootstrap, enabled = !syncBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Bootstrap from server")
                }
                OutlinedButton(onClick = onPush, enabled = !syncBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Push offline changes")
                }
                OutlinedButton(onClick = onRead, enabled = !syncBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Read request")
                }
                OutlinedButton(onClick = onDownload, enabled = !syncBusy, modifier = Modifier.fillMaxWidth()) {
                    Text("Download configuration")
                }
            }
        }

        Button(
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
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = keyNodeCountError == null && !syncBusy,
        ) {
            Text("Save Admin Menu settings")
        }
    }
}

@Composable
private fun AdminMenuToggle(
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
