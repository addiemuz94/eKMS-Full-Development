package com.ekms.mobile.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ekms.mobile.ui.common.CompanionCard

/**
 * Super Admin Mobile is deliberately a companion: it exposes personal Digital Key status,
 * approvals, alerts and terminal monitoring, but no full CRUD.
 */
@Composable
fun DashboardScreen(
    onOpenTerminals: () -> Unit,
    onOpenAccess: () -> Unit,
    onNotice: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CompanionCard("Vendor approvals", "Review pending access requests and approved expiry windows.") {
            onNotice("Vendor approvals will load from the backend once the API and sync layer are connected.")
        }
        CompanionCard("Alerts", "Overdue key returns, offline terminals and unresolved sync conflicts.") {
            onNotice("Alerts will be delivered from the central database and notification service.")
        }
        CompanionCard("Terminal status", "Monitor assigned sites and cabinet connectivity without full administration.") {
            onOpenTerminals()
        }
        CompanionCard("Keys & access", "Read the current key, cabinet-slot and exact-access summary.") {
            onOpenAccess()
        }
    }
}
