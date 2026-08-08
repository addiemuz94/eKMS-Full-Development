package com.ekms.mobile.ui.alerts

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
import androidx.compose.material3.OutlinedButton
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
import com.ekms.shared.api.AuthUserProfile
import com.ekms.shared.api.KeyAccessRequestDto
import com.ekms.shared.api.KeyAccessRequestStatus
import com.ekms.shared.domain.UserRole

/**
 * Alerts tab — pending decisions for SA/RA/PIC, plus own request status for Tech/Vendor.
 * Live FCM push (when registered) opens this tab; polling remains the in-app fallback.
 */
@Composable
fun AlertsScreen(
    apiClient: MobileApiClient,
    profile: AuthUserProfile?,
    refreshEpoch: Int = 0,
    onLiveStatus: (serverOk: Boolean, syncing: Boolean) -> Unit = { _, _ -> },
    onOpenAccess: () -> Unit,
    onNotice: (String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<KeyAccessRequestDto>>(emptyList()) }
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
            items = when {
                isAdminApprover -> {
                    apiClient.listKeyAccessRequests(status = "ALL").filter {
                        it.status == KeyAccessRequestStatus.PENDING ||
                            it.status == KeyAccessRequestStatus.PENDING_RA ||
                            it.status == KeyAccessRequestStatus.PENDING_PIC
                    }
                }
                isPic -> {
                    val picInbox = apiClient.listPicInbox()
                    val own = apiClient.listKeyAccessRequests(status = "ALL")
                    (picInbox + own)
                        .distinctBy { it.id }
                        .sortedByDescending { it.requestedAtEpochMillis }
                }
                else -> {
                    apiClient.listKeyAccessRequests(status = "ALL")
                        .sortedByDescending { it.requestedAtEpochMillis }
                }
            }
            hasData = true
            onLiveStatus(true, false)
        } catch (e: Exception) {
            loadError = e.message ?: "Failed to load alerts."
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
        Text("Alerts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            when {
                isAdminApprover ->
                    "Pending key access requests that need your decision. Updates automatically while Connected."
                isPic ->
                    "Vendor requests waiting for your PIC approval, plus status of your own requests."
                else ->
                    "Status of your key access requests. Updates automatically while Connected."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            loading -> CircularProgressIndicator()
            loadError != null && !hasData -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
            items.isEmpty() -> Text(
                "No alerts right now.",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> items.forEach { request ->
                AlertCard(request = request, onOpenAccess = onOpenAccess)
            }
        }
    }
}

@Composable
private fun AlertCard(request: KeyAccessRequestDto, onOpenAccess: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (request.status) {
                    KeyAccessRequestStatus.PENDING -> "Pending approval"
                    KeyAccessRequestStatus.PENDING_PIC -> "Awaiting PIC"
                    KeyAccessRequestStatus.PENDING_RA -> "Awaiting Regional Admin"
                    KeyAccessRequestStatus.APPROVED -> "Approved"
                    KeyAccessRequestStatus.REJECTED -> "Rejected"
                    KeyAccessRequestStatus.REVOKED -> "Revoked"
                    KeyAccessRequestStatus.EXPIRED -> "Expired — submit a new request"
                    KeyAccessRequestStatus.CANCELLED -> "Cancelled"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                request.reason?.takeIf { it.isNotBlank() } ?: "Key access request",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Status: ${request.status.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenAccess) { Text("Open Access") }
        }
    }
}
