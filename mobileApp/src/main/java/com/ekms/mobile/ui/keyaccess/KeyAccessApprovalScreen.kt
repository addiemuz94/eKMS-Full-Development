package com.ekms.mobile.ui.keyaccess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.mobile.data.MobileApiClient
import com.ekms.shared.api.KeyAccessRequestDto
import com.ekms.shared.api.KeyAccessRequestStatus
import kotlinx.coroutines.launch

/**
 * Regional Admin / Super Admin key-access queue: approve/reject PENDING, revoke APPROVED
 * (clears the PIN so terminal passkey-login fails). Scoping is server-side.
 */
@Composable
fun KeyAccessApprovalScreen(
    apiClient: MobileApiClient,
    refreshEpoch: Int = 0,
    onLiveStatus: (serverOk: Boolean, syncing: Boolean) -> Unit = { _, _ -> },
    onNotice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var requests by remember { mutableStateOf<List<KeyAccessRequestDto>>(emptyList()) }
    var busyRequestId by remember { mutableStateOf<String?>(null) }
    var hasData by remember { mutableStateOf(false) }

    suspend fun reload(showLoading: Boolean = true) {
        if (showLoading) loading = true
        onLiveStatus(true, true)
        loadError = null
        try {
            requests = apiClient.listKeyAccessRequests(status = "ALL")
                .filter {
                    it.status == KeyAccessRequestStatus.PENDING ||
                        it.status == KeyAccessRequestStatus.PENDING_RA ||
                        it.status == KeyAccessRequestStatus.APPROVED
                }
            hasData = true
            onLiveStatus(true, false)
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load key access requests."
            onLiveStatus(false, false)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(refreshEpoch) { reload(showLoading = !hasData) }

    fun approve(id: String) {
        busyRequestId = id
        scope.launch {
            try {
                apiClient.approveKeyAccessRequest(id)
                onNotice("Request approved — PIN issued to the requester.")
                reload()
            } catch (e: Exception) {
                onNotice(e.message ?: "Couldn't approve the request.")
            } finally {
                busyRequestId = null
            }
        }
    }

    fun reject(id: String) {
        busyRequestId = id
        scope.launch {
            try {
                apiClient.rejectKeyAccessRequest(id)
                onNotice("Request rejected.")
                reload()
            } catch (e: Exception) {
                onNotice(e.message ?: "Couldn't reject the request.")
            } finally {
                busyRequestId = null
            }
        }
    }

    fun revoke(id: String) {
        busyRequestId = id
        scope.launch {
            try {
                apiClient.revokeKeyAccessRequest(id)
                onNotice("Access revoked — PIN no longer works at the cabinet.")
                reload()
            } catch (e: Exception) {
                onNotice(e.message ?: "Couldn't revoke the request.")
            } finally {
                busyRequestId = null
            }
        }
    }

    val pending = requests.filter {
        it.status == KeyAccessRequestStatus.PENDING || it.status == KeyAccessRequestStatus.PENDING_RA
    }
    val approved = requests.filter { it.status == KeyAccessRequestStatus.APPROVED }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Key access requests", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        when {
            loading -> CircularProgressIndicator()
            loadError != null && !hasData -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
            else -> {
                Text("Pending", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (pending.isEmpty()) {
                    Text(
                        "No pending requests right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    pending.forEach { request ->
                        KeyAccessAdminCard(
                            request = request,
                            busy = busyRequestId == request.id,
                            onApprove = { approve(request.id) },
                            onReject = { reject(request.id) },
                            onRevoke = null,
                        )
                    }
                }

                Text("Active PINs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (approved.isEmpty()) {
                    Text(
                        "No approved passkeys to revoke.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    approved.forEach { request ->
                        KeyAccessAdminCard(
                            request = request,
                            busy = busyRequestId == request.id,
                            onApprove = null,
                            onReject = null,
                            onRevoke = { revoke(request.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyAccessAdminCard(
    request: KeyAccessRequestDto,
    busy: Boolean,
    onApprove: (() -> Unit)?,
    onReject: (() -> Unit)?,
    onRevoke: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                request.requesterDisplayName?.takeIf { it.isNotBlank() }
                    ?: request.siteName?.takeIf { it.isNotBlank() }
                    ?: "${request.requesterRole.name} · ${request.keyIds.size} key(s)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                buildString {
                    append(request.requesterRole.name)
                    request.siteName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    append(" · ${request.keyIds.size} key(s)")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (request.cabinetNames.isNotEmpty()) {
                Text(
                    "Cabinet: ${request.cabinetNames.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            request.reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                windowLabel(request)
                    ?: "Requested return within ${formatDuration(request.requestedDurationMinutes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onApprove != null && onReject != null) {
                    Button(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Text("Approve")
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Text("Reject")
                    }
                }
                if (onRevoke != null) {
                    OutlinedButton(
                        onClick = onRevoke,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) {
                        Text("Revoke PIN")
                    }
                }
            }
        }
    }
}
