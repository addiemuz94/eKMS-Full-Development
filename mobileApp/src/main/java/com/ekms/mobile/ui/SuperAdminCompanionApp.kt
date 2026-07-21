package com.ekms.mobile.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.CredentialKind
import com.ekms.shared.domain.KeySlotDemoData
import com.ekms.shared.domain.TerminalConnectionState

/**
 * Super Admin Mobile is deliberately a companion: it exposes personal Digital
 * Key status, approvals, alerts and terminal monitoring, but no full CRUD.
 */
@Composable
fun SuperAdminCompanionApp() {
    var tab by remember { mutableStateOf(MobileTab.OVERVIEW) }
    var notice by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Scaffold(
            topBar = {
                Surface(tonalElevation = 2.dp) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text("eKMS Digital Key", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Super Admin Companion", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
        ) { padding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val horizontalPadding = if (maxWidth < 520.dp) 16.dp else 24.dp
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .align(Alignment.TopCenter)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = horizontalPadding, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Digital Key Prototype", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Credential type: ${CredentialKind.STATIC_UID_DIGITAL_KEY_PROTOTYPE.name}")
                            Text("The permanent logical Digital Key is mapped to the physical NFC tag UID during this prototype.")
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(MobileTab.entries) { option ->
                            if (option == tab) {
                                Button(onClick = { tab = option }) { Text(option.label) }
                            } else {
                                OutlinedButton(onClick = { tab = option }) { Text(option.label) }
                            }
                        }
                    }

                    notice?.let { message ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Text(message, modifier = Modifier.padding(14.dp))
                        }
                    }

                    when (tab) {
                        MobileTab.OVERVIEW -> {
                            CompanionCard("Vendor approvals", "Review pending access requests and approved expiry windows.") {
                                notice = "Vendor approvals will load from the backend once the API and sync layer are connected."
                            }
                            CompanionCard("Alerts", "Overdue key returns, offline terminals and unresolved sync conflicts.") {
                                notice = "Alerts will be delivered from the central database and notification service."
                            }
                            CompanionCard("Terminal status", "Monitor assigned sites and cabinet connectivity without full administration.") {
                                tab = MobileTab.TERMINALS
                            }
                            CompanionCard("Keys & access", "Read the current key, cabinet-slot and exact-access summary.") {
                                tab = MobileTab.ACCESS
                            }
                        }

                        MobileTab.TERMINALS -> {
                            Text("Terminal status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            MobileTerminalCard(
                                siteName = "Kuala Lumpur HQ",
                                terminalName = "HQ Main Cabinet",
                                state = TerminalConnectionState.ONLINE,
                                detail = "Last sync: local demo",
                            )
                            MobileTerminalCard(
                                siteName = "Johor Service Hub",
                                terminalName = "Service Cabinet",
                                state = TerminalConnectionState.SETUP_REQUIRED,
                                detail = "Awaiting supervised cabinet configuration",
                            )
                            Text(
                                "This companion view is read-only by design. Site and terminal editing stays on Website and Terminal Super Admin screens.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        MobileTab.ACCESS -> {
                            Text("Keys & access", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            CompanionCard(
                                title = "Registered physical keys",
                                description = "${KeySlotDemoData.keys().size} local demo key record(s). Fob identifiers are never displayed on Mobile.",
                            ) {
                                notice = "Website and Terminal Super Admin manage key records; this companion view is read-only."
                            }
                            CompanionCard(
                                title = "Cabinet slot mapping",
                                description = "${KeySlotDemoData.slots().size} local demo slot mapping(s). Key-node addresses are configured on Website or Terminal only.",
                            ) {
                                notice = "No cabinet command can be sent from the Mobile companion."
                            }
                            CompanionCard(
                                title = "Exact access grants",
                                description = "${KeySlotDemoData.accessGrants().size} local demo grant(s). The backend will deliver the approved user-key scope and expiry later.",
                            ) {
                                notice = "Access grants remain read-only on Mobile until the central authorization API is connected."
                            }
                            Text(
                                "This screen adapts to phone and tablet widths, but does not create, edit or revoke access. " +
                                        "Those protected functions remain on Website and Terminal Super Admin screens.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        MobileTab.ALERTS -> {
                            CompanionCard("No critical alert in local demo", "Backend data will replace this sample list.") {
                                notice = "No action is sent from this local companion demo."
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionCard(title: String, description: String, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpen) { Text("Open") }
        }
    }
}

@Composable
private fun MobileTerminalCard(
    siteName: String,
    terminalName: String,
    state: TerminalConnectionState,
    detail: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(terminalName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(siteName)
            Text("Status: ${state.mobileLabel}")
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private enum class MobileTab(val label: String) {
    OVERVIEW("Overview"),
    TERMINALS("Terminals"),
    ACCESS("Access"),
    ALERTS("Alerts"),
}

private val TerminalConnectionState.mobileLabel: String
    get() = when (this) {
        TerminalConnectionState.UNKNOWN -> "Unknown"
        TerminalConnectionState.ONLINE -> "Online"
        TerminalConnectionState.OFFLINE -> "Offline"
        TerminalConnectionState.SETUP_REQUIRED -> "Setup required"
    }