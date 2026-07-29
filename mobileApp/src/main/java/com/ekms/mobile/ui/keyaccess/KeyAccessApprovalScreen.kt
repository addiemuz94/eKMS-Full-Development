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
import kotlinx.coroutines.launch

/**
 * Regional Admin / Super Admin's Key Access Request approval screen. Scoping is entirely
 * server-side (`GET /key-access-requests` region-scopes Regional Admin automatically via
 * `assignedRegionIdsForUser`/`isRegionAssignedToUser`, and leaves Super Admin unscoped) — this
 * screen never filters by role itself, it just displays whatever the backend already decided
 * this caller may see. Same "Super Admin keeps full parity everywhere" pattern as every other
 * Regional-Admin-scoped feature in this project (cabinet settings, office hours, access grants).
 *
 * Approve/Reject are given equal visual weight — same size, same row, differing only in
 * ACCEPT/CANCEL-style tone — mirroring terminalApp's Phase 9C fix to `TerminalVendorPasskeyScreen`
 * (that screen previously made Reject look like a lesser action purely from component choice,
 * despite both being equally final). The just-generated passkey is deliberately NOT shown here —
 * it was never meant for the approver, only the requester (see `KeyAccessRequestScreen`'s status
 * list, which is the one place that value is ever surfaced to anyone).
 */
@Composable
fun KeyAccessApprovalScreen(apiClient: MobileApiClient, onNotice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var requests by remember { mutableStateOf<List<KeyAccessRequestDto>>(emptyList()) }
    var busyRequestId by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        loadError = null
        try {
            requests = apiClient.listKeyAccessRequests(status = "PENDING")
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load pending requests."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun approve(id: String) {
        busyRequestId = id
        scope.launch {
            try {
                apiClient.approveKeyAccessRequest(id)
                onNotice("Request approved.")
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

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Key access requests", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        when {
            loading -> CircularProgressIndicator()
            loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
            requests.isEmpty() -> Text(
                "No pending requests right now.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> requests.forEach { request ->
                val busy = busyRequestId == request.id
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${request.requesterRole.name} · ${request.keyIds.size} key(s)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Requested return within ${formatDuration(request.requestedDurationMinutes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { approve(request.id) },
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                            ) {
                                Text("Approve")
                            }
                            OutlinedButton(
                                onClick = { reject(request.id) },
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                            ) {
                                Text("Reject")
                            }
                        }
                    }
                }
            }
        }
    }
}
