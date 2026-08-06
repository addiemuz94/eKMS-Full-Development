package com.ekms.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ekms.shared.api.ApproveVendorPasskeyRequestResponse
import com.ekms.shared.api.VendorPasskeyRequestDto
import com.ekms.shared.api.VendorPasskeyRequestStatus
import com.ekms.terminal.data.TerminalUserRole
import kotlinx.coroutines.launch

/**
 * Phase 7, item 16. Backend routes (`vendor-passkey-requests` list/approve/reject) were built in
 * Phase 1; this is the first terminal-side screen to call them.
 *
 * **Judgment call, flagged per the phase spec rather than decided silently:** the matrix frames
 * this item as Regional Admin's job on web (Super Admin has no dedicated web UI for it there).
 * Since Super Admin has unrestricted backend access regardless, this screen builds full
 * approve/reject for Super Admin too, matching the general "full parity with web" rule for every
 * other item — but if this item is meant to be Regional-Admin-exclusive *by design*, not just by
 * omission, that's a real product decision this pass made an assumption about. Confirm or correct.
 *
 * Regional Admin: view-only list of pending requests, **no** approve/reject controls — those
 * actions happen on web/mobile only, per the matrix's terminal row for this item ("View only"
 * even though Regional Admin edits it on web).
 *
 * Phase 9C (visual/theme only — the `canEdit` gate and the judgment call above are unchanged):
 * status now renders via [SoftAssistChip] (Pending → attention, Approved → success, Rejected →
 * neutral) instead of plain text; Approve/Reject are [IconActionButton] ([ActionButtonType.ACCEPT]
 * / [ActionButtonType.CANCEL]) instead of a filled [androidx.compose.material3.Button] +
 * [androidx.compose.material3.TextButton] pair — that pairing gave Reject visibly less visual
 * weight than Approve (plain text vs. a solid fill) despite both being equally real, equally
 * final actions; both are now the same size/shape, differing only in ACCEPT/CANCEL tone. New
 * [VendorPasskeyEmptyState] replaces the bare "No vendor passkey requests" text, same
 * icon+title+message approach as `TerminalKeyMenuScreen`'s `KeyMenuEmptyState` (Phase 9B).
 */
@Composable
fun TerminalVendorPasskeyScreen(
    padding: PaddingValues,
    role: TerminalUserRole,
    siteId: String,
    onBack: () -> Unit,
    onLoad: suspend (siteId: String) -> List<VendorPasskeyRequestDto>,
    onApprove: suspend (id: String) -> ApproveVendorPasskeyRequestResponse,
    onReject: suspend (id: String) -> VendorPasskeyRequestDto,
) {
    val canEdit = role == TerminalUserRole.SUPER_ADMIN
    val scope = rememberCoroutineScope()

    var refreshToken by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var requests by remember { mutableStateOf<List<VendorPasskeyRequestDto>>(emptyList()) }
    var busyRequestId by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    // Shown exactly once, same "shown once" treatment as terminal pairing codes — never re-read
    // from anywhere after this, and cleared the moment a different action is taken.
    var justApprovedCode by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(siteId, refreshToken) {
        loading = true
        loadError = null
        try {
            requests = onLoad(siteId)
        } catch (error: Exception) {
            loadError = error.message ?: "Could not load vendor passkey requests."
        } finally {
            loading = false
        }
    }

    fun approve(id: String) {
        busyRequestId = id
        actionError = null
        scope.launch {
            try {
                val response = onApprove(id)
                justApprovedCode = id to response.passkeyCode
                refreshToken++
            } catch (error: Exception) {
                actionError = error.message ?: "Could not approve this request."
            } finally {
                busyRequestId = null
            }
        }
    }

    fun reject(id: String) {
        busyRequestId = id
        actionError = null
        justApprovedCode = null
        scope.launch {
            try {
                onReject(id)
                refreshToken++
            } catch (error: Exception) {
                actionError = error.message ?: "Could not reject this request."
            } finally {
                busyRequestId = null
            }
        }
    }

    TerminalPage(padding) {
        BackButton(onBack)
        HeaderCard(
            title = "Vendor passkey approval",
            description = if (canEdit) {
                "Approve or reject pending vendor passkey requests for this terminal's site."
            } else {
                "View-only: pending vendor passkey requests for this terminal's site. Approve or reject on the web portal."
            },
        )
        loadError?.let { message -> SuperAdminNoticeCard(message) }
        actionError?.let { message -> SuperAdminNoticeCard(message) }
        justApprovedCode?.let { (id, code) ->
            SoftCard(contentPadding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Passkey approved", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Code $code for request ${id.take(8)}… — shown once, write it down now.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (loading) {
            Text("Loading vendor passkey requests…", style = MaterialTheme.typography.bodyMedium)
        } else if (requests.isEmpty()) {
            VendorPasskeyEmptyState()
        } else {
            requests.forEach { request ->
                SoftCard(contentPadding = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Only the vendor's raw record id is available here — the backend
                        // schema for this item is deliberately minimal (see Phase 1's
                        // vendor_passkey_requests table doc) and doesn't include a display
                        // name; resolving one is a follow-up, not part of this pass.
                        Text("Vendor ${request.vendorUserId.take(8)}…", fontWeight = FontWeight.SemiBold)
                        // Phase 9C: SoftAssistChip instead of plain "Status: X" text — reuses
                        // the same success/attention/neutral tone language every other status
                        // indicator in the sweep uses, and is theme-correct in both modes via
                        // Phase 9B's softToneColors fix (not re-litigated here).
                        SoftAssistChip(
                            text = when (request.status) {
                                VendorPasskeyRequestStatus.PENDING -> "Pending"
                                VendorPasskeyRequestStatus.APPROVED -> "Approved"
                                VendorPasskeyRequestStatus.REJECTED -> "Rejected"
                            },
                            attention = request.status == VendorPasskeyRequestStatus.PENDING,
                            success = request.status == VendorPasskeyRequestStatus.APPROVED,
                        )
                        Text(
                            "Requested at epoch ${request.requestedAtEpochMillis}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (canEdit && request.status == VendorPasskeyRequestStatus.PENDING) {
                            // Phase 9C: both actions are now IconActionButton — matching size/
                            // shape, differing only by ACCEPT (filled, success tone) vs. CANCEL
                            // (outlined, neutral tone). The old Button+TextButton pairing gave
                            // Reject visibly less visual weight than Approve despite both being
                            // equally real, final actions; that imbalance is what this fixes.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                IconActionButton(
                                    type = ActionButtonType.ACCEPT,
                                    label = "Approve",
                                    onClick = { approve(request.id) },
                                    modifier = Modifier.weight(1f),
                                    enabled = busyRequestId == null,
                                )
                                IconActionButton(
                                    type = ActionButtonType.CANCEL,
                                    label = "Reject",
                                    onClick = { reject(request.id) },
                                    modifier = Modifier.weight(1f),
                                    enabled = busyRequestId == null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Phase 9C: replaces the old bare `Text("No vendor passkey requests...")` with an actual
 * designed empty state — same icon+title+message shape as `TerminalKeyMenuScreen`'s
 * `KeyMenuEmptyState` (Phase 9B), same `MaterialTheme.colorScheme` tokens, no hardcoded colors.
 */
@Composable
private fun VendorPasskeyEmptyState() {
    SoftCard(contentPadding = 28.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Readability pass: 56dp -> 67dp box, corner 18dp -> 22dp (both x1.2).
            Box(
                modifier = Modifier
                    .size(67.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "No pending requests",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "There are no vendor passkey requests for this site right now.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
