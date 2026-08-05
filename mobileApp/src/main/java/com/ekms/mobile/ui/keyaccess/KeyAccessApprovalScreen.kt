package com.ekms.mobile.ui.keyaccess

import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ekms.mobile.data.MobileApiClient
import com.ekms.mobile.ui.common.ConfirmDialogHost
import com.ekms.mobile.ui.common.ConfirmRequest
import com.ekms.mobile.ui.common.IconActionButton
import com.ekms.mobile.ui.common.MobileActionButtonType
import com.ekms.shared.api.KeyAccessRequestDocumentMeta
import com.ekms.shared.api.KeyAccessRequestDto
import com.ekms.shared.api.KeyAccessRequestStatus
import kotlinx.coroutines.launch
import java.io.File

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
    var confirmRequest by remember { mutableStateOf<ConfirmRequest?>(null) }

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
            loadError = e.message ?: "Failed to load key access requests."
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
                onNotice(e.message ?: "Failed to approve the request.")
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
                onNotice(e.message ?: "Failed to reject the request.")
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
                onNotice(e.message ?: "Failed to revoke the request.")
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
                            apiClient = apiClient,
                            request = request,
                            busy = busyRequestId == request.id,
                            onApprove = { approve(request.id) },
                            onReject = {
                                confirmRequest = ConfirmRequest(
                                    title = "Reject this request?",
                                    body = "The requester will need to submit a new request if they still need access.",
                                    confirmLabel = "Reject",
                                    onConfirm = { reject(request.id) },
                                )
                            },
                            onRevoke = null,
                            onNotice = onNotice,
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
                            apiClient = apiClient,
                            request = request,
                            busy = busyRequestId == request.id,
                            onApprove = null,
                            onReject = null,
                            onRevoke = {
                                // Most consequential of the 5 gated actions this pass — kills an
                                // already-issued PIN that may be in active use right now, worded
                                // to make that concrete rather than a generic "are you sure."
                                confirmRequest = ConfirmRequest(
                                    title = "Revoke this PIN?",
                                    body = "This PIN may already be in use at the cabinet right now. " +
                                        "Revoking it immediately stops it from working — the requester " +
                                        "will be locked out mid-use if they're actively using it.",
                                    confirmLabel = "Revoke PIN",
                                    onConfirm = { revoke(request.id) },
                                )
                            },
                            onNotice = onNotice,
                        )
                    }
                }
            }
        }
    }

    ConfirmDialogHost(confirmRequest) { confirmRequest = null }
}

@Composable
private fun KeyAccessAdminCard(
    apiClient: MobileApiClient,
    request: KeyAccessRequestDto,
    busy: Boolean,
    onApprove: (() -> Unit)?,
    onReject: (() -> Unit)?,
    onRevoke: (() -> Unit)?,
    onNotice: (String) -> Unit,
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
            KeyAccessDocumentsSection(
                apiClient = apiClient,
                requestId = request.id,
                documents = request.documents,
                onNotice = onNotice,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onApprove != null && onReject != null) {
                    IconActionButton(
                        type = MobileActionButtonType.CONFIRM,
                        onClick = onApprove,
                        modifier = Modifier.weight(1f),
                        label = "Approve",
                        enabled = !busy,
                    )
                    IconActionButton(
                        type = MobileActionButtonType.CANCEL,
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                        label = "Reject",
                        enabled = !busy,
                    )
                }
                if (onRevoke != null) {
                    IconActionButton(
                        type = MobileActionButtonType.DESTRUCTIVE,
                        onClick = onRevoke,
                        modifier = Modifier.fillMaxWidth(),
                        label = "Revoke PIN",
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

/**
 * Attached-document list (Vendor Work Permit/NIOSH/IC) for a PIC/Regional Admin to review before
 * acting on a request — or for the requester to see their own upload back. Renders nothing for a
 * Technician-only request (`documents` is always empty there, see [KeyAccessRequestDto.documents]).
 * Shared between [KeyAccessApprovalScreen] (RA queue) and [KeyAccessRequestScreen] (PIC inbox) —
 * both live in this package, so no export/import needed beyond `internal` visibility.
 *
 * Download-then-view mirrors `LogsScreen.kt`'s Activity Report PDF export exactly: fetch bytes
 * (authenticated), write to the app's `Documents` external-files dir (already declared in
 * `file_paths.xml` for the existing `${applicationId}.fileprovider` authority — no new manifest
 * entry needed), then hand the whole viewer choice to the OS via `ACTION_VIEW` rather than
 * building an in-app document/image/PDF renderer.
 */
@Composable
internal fun KeyAccessDocumentsSection(
    apiClient: MobileApiClient,
    requestId: String,
    documents: List<KeyAccessRequestDocumentMeta>,
    onNotice: (String) -> Unit,
) {
    if (documents.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openingDocId by remember { mutableStateOf<String?>(null) }

    fun open(doc: KeyAccessRequestDocumentMeta) {
        openingDocId = doc.id
        scope.launch {
            try {
                val bytes = apiClient.downloadKeyAccessRequestDocument(requestId, doc.id)
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
                val safeName = doc.fileName.trim().ifBlank { "${doc.docKind.lowercase()}-${doc.id}" }
                val file = File(dir, "kar-${doc.id}-$safeName")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, doc.contentType.ifBlank { "application/octet-stream" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(view, "Open ${doc.docKind}"))
            } catch (e: Exception) {
                onNotice(e.message ?: "Failed to open ${doc.docKind}.")
            } finally {
                openingDocId = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${documents.size} document(s) attached",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        documents.forEach { doc ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${doc.docKind} · ${formatDocumentSize(doc.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { open(doc) },
                    enabled = openingDocId != doc.id,
                ) {
                    Text(if (openingDocId == doc.id) "Opening…" else "View")
                }
            }
        }
    }
}

private fun formatDocumentSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
