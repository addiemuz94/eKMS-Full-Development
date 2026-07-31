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
 * Alerts tab — interim wiring until FCM push (Phase 3).
 * Shows key access request status from the live API (pending for approvers; own requests for others).
 */
@Composable
fun AlertsScreen(
    apiClient: MobileApiClient,
    profile: AuthUserProfile?,
    onOpenAccess: () -> Unit,
    onNotice: (String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<KeyAccessRequestDto>>(emptyList()) }

    val isApprover =
        profile?.role == UserRole.SUPER_ADMIN || profile?.role == UserRole.REGIONAL_ADMIN

    LaunchedEffect(profile?.id) {
        loading = true
        loadError = null
        try {
            val all = apiClient.listKeyAccessRequests(status = "ALL")
            items = if (isApprover) {
                all.filter { it.status == KeyAccessRequestStatus.PENDING }
            } else {
                all.sortedByDescending { it.requestedAtEpochMillis }
            }
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load alerts."
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
        Text("Alerts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            if (isApprover) {
                "Pending key access requests that need your decision."
            } else {
                "Status of your key access requests. Push notifications come in a later update."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            loading -> CircularProgressIndicator()
            loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
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
