package com.ekms.mobile.ui.terminals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.ekms.mobile.data.MobileApiClient
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.TerminalDto
import com.ekms.shared.domain.TerminalConnectionState

/**
 * Live terminal list from `GET /v1/admin/terminals` (server-scoped by role).
 */
@Composable
fun TerminalsScreen(
    apiClient: MobileApiClient,
    onNotice: (String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var terminals by remember { mutableStateOf<List<TerminalDto>>(emptyList()) }
    var sitesById by remember { mutableStateOf<Map<String, SiteDto>>(emptyMap()) }

    LaunchedEffect(Unit) {
        loading = true
        loadError = null
        try {
            val sites = apiClient.listSites()
            sitesById = sites.associateBy { it.id }
            terminals = apiClient.listTerminals()
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load terminals."
            onNotice(loadError!!)
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Terminals", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Key cabinets at your permitted locations. Read-only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            loading -> CircularProgressIndicator()
            loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
            terminals.isEmpty() -> Text(
                "No terminals found for your locations.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> terminals.forEach { terminal ->
                MobileTerminalCard(
                    terminal = terminal,
                    siteName = sitesById[terminal.siteId]?.name ?: terminal.siteId,
                )
            }
        }
    }
}

@Composable
private fun MobileTerminalCard(terminal: TerminalDto, siteName: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(terminal.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(siteName, style = MaterialTheme.typography.bodyMedium)
            Text("Status: ${terminal.connectionState.mobileLabel}", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (terminal.paired) "Paired" else "Not paired",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
