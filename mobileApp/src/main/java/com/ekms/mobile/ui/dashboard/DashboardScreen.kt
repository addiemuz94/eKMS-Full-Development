package com.ekms.mobile.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.ekms.mobile.ui.common.CompanionCard
import com.ekms.shared.api.AuthUserProfile
import com.ekms.shared.api.KeyAccessRequestStatus
import com.ekms.shared.domain.UserRole

/**
 * Overview tab — live counts from the API, scoped by the signed-in role on the server.
 */
@Composable
fun DashboardScreen(
    apiClient: MobileApiClient,
    profile: AuthUserProfile?,
    onOpenTerminals: () -> Unit,
    onOpenAccess: () -> Unit,
    onOpenAlerts: () -> Unit,
    onNotice: (String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var terminalCount by remember { mutableStateOf(0) }
    var pendingCount by remember { mutableStateOf(0) }
    var myRequestCount by remember { mutableStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val isApprover =
        profile?.role == UserRole.SUPER_ADMIN || profile?.role == UserRole.REGIONAL_ADMIN

    LaunchedEffect(profile?.id) {
        loading = true
        loadError = null
        try {
            terminalCount = apiClient.listTerminals().size
            val requests = apiClient.listKeyAccessRequests(status = "ALL")
            pendingCount = requests.count { it.status == KeyAccessRequestStatus.PENDING }
            myRequestCount = requests.size
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load overview."
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
        Text("Overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Summary for your permitted locations.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            loading -> CircularProgressIndicator()
            loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
            else -> {
                if (isApprover) {
                    CompanionCard(
                        title = "Pending access",
                        description = when (pendingCount) {
                            0 -> "No pending key access requests."
                            1 -> "1 request waiting for approval."
                            else -> "$pendingCount requests waiting for approval."
                        },
                        actionLabel = "View",
                        onOpen = onOpenAccess,
                    )
                    CompanionCard(
                        title = "Alerts",
                        description = when (pendingCount) {
                            0 -> "No alerts right now."
                            1 -> "1 item needs attention."
                            else -> "$pendingCount items need attention."
                        },
                        actionLabel = "View",
                        onOpen = onOpenAlerts,
                    )
                } else {
                    CompanionCard(
                        title = "Key access",
                        description = when (myRequestCount) {
                            0 -> "No key access requests yet. Apply for exception access when needed."
                            1 -> "1 key access request on file."
                            else -> "$myRequestCount key access requests on file."
                        },
                        actionLabel = "Open",
                        onOpen = onOpenAccess,
                    )
                    CompanionCard(
                        title = "Alerts",
                        description = "See request status updates here.",
                        actionLabel = "View",
                        onOpen = onOpenAlerts,
                    )
                }
                CompanionCard(
                    title = "Terminals",
                    description = when (terminalCount) {
                        0 -> "No cabinets at your permitted locations."
                        1 -> "1 key cabinet at your permitted locations."
                        else -> "$terminalCount key cabinets at your permitted locations."
                    },
                    actionLabel = "View",
                    onOpen = onOpenTerminals,
                )
            }
        }
    }
}
