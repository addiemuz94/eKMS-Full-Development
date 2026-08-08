package com.ekms.mobile.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
 * Re-fetches when [refreshEpoch] changes (resume + 30s poll from the shell).
 */
@Composable
fun DashboardScreen(
    apiClient: MobileApiClient,
    profile: AuthUserProfile?,
    refreshEpoch: Int = 0,
    onLiveStatus: (serverOk: Boolean, syncing: Boolean) -> Unit = { _, _ -> },
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
    var hasData by remember { mutableStateOf(false) }

    val isAdminApprover =
        profile?.role == UserRole.SUPER_ADMIN || profile?.role == UserRole.REGIONAL_ADMIN
    val isPic = profile?.role == UserRole.TECHNICIAN

    LaunchedEffect(profile?.id, refreshEpoch) {
        val showSpinner = !hasData
        if (showSpinner) loading = true
        onLiveStatus(true, true)
        loadError = null
        try {
            terminalCount = apiClient.listTerminals().size
            if (isAdminApprover) {
                val requests = apiClient.listKeyAccessRequests(status = "ALL")
                pendingCount = requests.count {
                    it.status == KeyAccessRequestStatus.PENDING ||
                        it.status == KeyAccessRequestStatus.PENDING_RA ||
                        it.status == KeyAccessRequestStatus.PENDING_PIC
                }
                myRequestCount = requests.size
            } else if (isPic) {
                val picInbox = apiClient.listPicInbox()
                val own = apiClient.listKeyAccessRequests(status = "ALL")
                pendingCount = picInbox.size
                myRequestCount = own.size
            } else {
                val requests = apiClient.listKeyAccessRequests(status = "ALL")
                pendingCount = 0
                myRequestCount = requests.size
            }
            hasData = true
            onLiveStatus(true, false)
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load overview."
            onLiveStatus(false, false)
            if (!hasData) onNotice(loadError!!)
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
            "Summary for your permitted locations. Updates automatically while Connected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            loading -> CircularProgressIndicator()
            loadError != null && !hasData -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
            else -> {
                if (isAdminApprover || isPic) {
                    CompanionCard(
                        title = if (isPic) "PIC inbox" else "Pending access",
                        description = when (pendingCount) {
                            0 -> if (isPic) "No vendor requests waiting for your PIC approval." else "No pending key access requests."
                            1 -> if (isPic) "1 vendor request waiting for your PIC approval." else "1 request waiting for approval."
                            else -> if (isPic) "$pendingCount vendor requests waiting for your PIC approval." else "$pendingCount requests waiting for approval."
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
                    if (isPic) {
                        CompanionCard(
                            title = "My requests",
                            description = when (myRequestCount) {
                                0 -> "No key access requests of your own yet."
                                1 -> "1 of your key access requests on file."
                                else -> "$myRequestCount of your key access requests on file."
                            },
                            actionLabel = "Open",
                            onOpen = onOpenAccess,
                        )
                    }
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
