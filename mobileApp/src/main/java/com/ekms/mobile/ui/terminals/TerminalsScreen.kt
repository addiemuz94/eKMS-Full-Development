package com.ekms.mobile.ui.terminals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.KeySlotDemoData
import com.ekms.shared.domain.ManagedTerminalOption
import com.ekms.shared.domain.SuperAdminDemoData
import com.ekms.shared.domain.TerminalConnectionState

/**
 * Reads terminal + site names from the same shared demo data source terminalApp also uses
 * (`KeySlotDemoData.terminals`, `SuperAdminDemoData.sites`) — the prior version of this screen
 * hardcoded site/terminal name strings that didn't match `KeySlotDemoData` at all. mobileApp has
 * no network layer yet, so connection state is honestly shown as UNKNOWN rather than a fabricated
 * ONLINE/OFFLINE value.
 */
@Composable
fun TerminalsScreen() {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Terminal status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        KeySlotDemoData.terminals.forEach { terminal ->
            MobileTerminalCard(terminal)
        }

        Text(
            "This companion view is read-only by design. Site and terminal editing stays on Website and Terminal Super Admin screens.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MobileTerminalCard(terminal: ManagedTerminalOption) {
    val siteName = SuperAdminDemoData.sites.firstOrNull { it.id == terminal.siteId }?.label ?: terminal.siteId
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(terminal.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(siteName)
            Text("Status: ${TerminalConnectionState.UNKNOWN.mobileLabel}")
            Text("Local demo data — live status requires backend sync.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val TerminalConnectionState.mobileLabel: String
    get() = when (this) {
        TerminalConnectionState.UNKNOWN -> "Unknown"
        TerminalConnectionState.ONLINE -> "Online"
        TerminalConnectionState.OFFLINE -> "Offline"
        TerminalConnectionState.SETUP_REQUIRED -> "Setup required"
    }
