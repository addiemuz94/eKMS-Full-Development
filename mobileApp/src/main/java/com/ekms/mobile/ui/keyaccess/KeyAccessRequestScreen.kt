package com.ekms.mobile.ui.keyaccess

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.ekms.mobile.data.MobileApiClient
import com.ekms.mobile.ui.theme.readout
import com.ekms.shared.api.AuthUserProfile
import com.ekms.shared.api.CreateKeyAccessRequestRequest
import com.ekms.shared.api.KeyAccessRequestDocumentUpload
import com.ekms.shared.api.KeyAccessRequestDto
import com.ekms.shared.api.KeyAccessRequestStatus
import com.ekms.shared.api.KeyDto
import com.ekms.shared.api.SiteDto
import com.ekms.shared.api.SitePicCandidateDto
import com.ekms.shared.domain.UserRole
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Only B — exception Key Access Apply. Vendor adds PIC + documents (Stage 1 → RA).
 * Expired requests require a new submit (no revive). Technicians also see PIC inbox here.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeyAccessRequestScreen(
    apiClient: MobileApiClient,
    profile: AuthUserProfile,
    refreshEpoch: Int = 0,
    onLiveStatus: (serverOk: Boolean, syncing: Boolean) -> Unit = { _, _ -> },
    onNotice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isVendor = profile.role == UserRole.VENDOR
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var exceptionSites by remember { mutableStateOf<List<SiteDto>>(emptyList()) }
    var keysAtSite by remember { mutableStateOf<List<KeyDto>>(emptyList()) }
    var myRequests by remember { mutableStateOf<List<KeyAccessRequestDto>>(emptyList()) }
    var picInbox by remember { mutableStateOf<List<KeyAccessRequestDto>>(emptyList()) }
    var sitePics by remember { mutableStateOf<List<SitePicCandidateDto>>(emptyList()) }
    var selectedSiteId by remember { mutableStateOf<String?>(null) }
    var selectedKeyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedPicId by remember { mutableStateOf<String?>(null) }
    var documents by remember { mutableStateOf<List<KeyAccessRequestDocumentUpload>>(emptyList()) }
    var pendingDocKind by remember { mutableStateOf("WORK_PERMIT") }
    var reason by remember { mutableStateOf("") }
    var pickupEpochMillis by remember { mutableStateOf(defaultPickupEpochMillis()) }
    var returnEpochMillis by remember { mutableStateOf(defaultReturnEpochMillis()) }
    var submitting by remember { mutableStateOf(false) }
    var loadingKeys by remember { mutableStateOf(false) }
    var selectedApprovedId by remember { mutableStateOf<String?>(null) }
    var approvedMenuExpanded by remember { mutableStateOf(false) }
    var busyPicId by remember { mutableStateOf<String?>(null) }
    var busyCancelId by remember { mutableStateOf<String?>(null) }
    var hasData by remember { mutableStateOf(false) }

    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@rememberLauncherForActivityResult
            if (bytes.size > 5 * 1024 * 1024) {
                onNotice("Document must be 5 MB or smaller.")
                return@rememberLauncherForActivityResult
            }
            val name = uri.lastPathSegment ?: "document.bin"
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            documents = documents.filter { it.docKind != pendingDocKind } + KeyAccessRequestDocumentUpload(
                docKind = pendingDocKind,
                fileName = name,
                contentType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                contentBase64 = b64,
            )
            onNotice("Attached $pendingDocKind ($name)")
        } catch (e: Exception) {
            onNotice(e.message ?: "Couldn't read document.")
        }
    }

    suspend fun reload(showLoading: Boolean = true) {
        if (showLoading) loading = true
        onLiveStatus(true, true)
        loadError = null
        try {
            exceptionSites = apiClient.listExceptionSites()
            myRequests = apiClient.listKeyAccessRequests(status = "ALL")
            if (profile.role == UserRole.TECHNICIAN) {
                picInbox = apiClient.listPicInbox()
            }
            val approvedIds = myRequests
                .filter { it.status == KeyAccessRequestStatus.APPROVED }
                .map { it.id }
                .toSet()
            if (selectedApprovedId !in approvedIds) {
                selectedApprovedId = null
            }
            hasData = true
            onLiveStatus(true, false)
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load exception sites and requests."
            onLiveStatus(false, false)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(refreshEpoch) { reload(showLoading = !hasData) }

    LaunchedEffect(selectedSiteId) {
        val siteId = selectedSiteId
        selectedKeyIds = emptySet()
        keysAtSite = emptyList()
        sitePics = emptyList()
        selectedPicId = null
        if (siteId == null) return@LaunchedEffect
        loadingKeys = true
        try {
            keysAtSite = apiClient.listExceptionSiteKeys(siteId)
            if (isVendor) {
                sitePics = apiClient.listSitePics(siteId)
            }
        } catch (e: Exception) {
            onNotice(e.message ?: "Couldn't load keys for that site.")
        } finally {
            loadingKeys = false
        }
    }

    fun toggleKey(key: KeyDto) {
        selectedKeyIds = if (key.id in selectedKeyIds) selectedKeyIds - key.id else selectedKeyIds + key.id
    }

    fun submit() {
        val siteId = selectedSiteId ?: return
        val pickup = pickupEpochMillis
        val returnAt = returnEpochMillis
        if (returnAt <= pickup) {
            onNotice("Return must be after pickup.")
            return
        }
        if (reason.isBlank()) {
            onNotice("Enter a reason.")
            return
        }
        if (selectedKeyIds.isEmpty()) {
            onNotice("Select at least one key.")
            return
        }
        if (isVendor) {
            if (selectedPicId == null) {
                onNotice("Select a Person In Charge (Technician at this site).")
                return
            }
            if (documents.isEmpty()) {
                onNotice("Attach at least one document (Work Permit, NIOSH, or IC).")
                return
            }
        }
        submitting = true
        scope.launch {
            try {
                apiClient.createKeyAccessRequest(
                    CreateKeyAccessRequestRequest(
                        siteId = siteId,
                        keyIds = selectedKeyIds,
                        reason = reason.trim(),
                        pickupAtEpochMillis = pickup,
                        returnAtEpochMillis = returnAt,
                        picUserId = if (isVendor) selectedPicId else null,
                        documents = if (isVendor) documents else emptyList(),
                    ),
                )
                selectedKeyIds = emptySet()
                reason = ""
                documents = emptyList()
                selectedPicId = null
                onNotice(
                    if (isVendor) "Request submitted — waiting for PIC, then Regional Admin."
                    else "Request submitted. A Regional Admin will review it.",
                )
                reload()
            } catch (e: Exception) {
                onNotice(e.message ?: "Couldn't submit the request.")
            } finally {
                submitting = false
            }
        }
    }

    fun cancel(id: String) {
        busyCancelId = id
        scope.launch {
            try {
                apiClient.cancelKeyAccessRequest(id)
                if (selectedApprovedId == id) selectedApprovedId = null
                onNotice("Request cancelled. Submit a new request if you still need access.")
                reload()
            } catch (e: Exception) {
                onNotice(e.message ?: "Couldn't cancel the request.")
            } finally {
                busyCancelId = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Apply key access", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "Exception access — request a location outside your standing assignments.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${profile.displayName} · ${profile.email}",
            style = MaterialTheme.typography.bodyMedium,
        )

        when {
            loading -> CircularProgressIndicator()
            loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
            else -> {
                if (picInbox.isNotEmpty()) {
                    Text("PIC inbox (Vendor Stage 1)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    picInbox.forEach { request ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(request.siteName ?: request.siteId, fontWeight = FontWeight.SemiBold)
                                Text(request.requesterDisplayName ?: request.requesterUserId)
                                Text(request.reason ?: "", style = MaterialTheme.typography.bodySmall)
                                KeyAccessDocumentsSection(
                                    apiClient = apiClient,
                                    requestId = request.id,
                                    documents = request.documents,
                                    onNotice = onNotice,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            busyPicId = request.id
                                            scope.launch {
                                                try {
                                                    apiClient.picApproveKeyAccessRequest(request.id)
                                                    onNotice("PIC approved — sent to Regional Admin.")
                                                    reload()
                                                } catch (e: Exception) {
                                                    onNotice(e.message ?: "PIC approve failed.")
                                                } finally {
                                                    busyPicId = null
                                                }
                                            }
                                        },
                                        enabled = busyPicId != request.id,
                                    ) { Text("Approve as PIC") }
                                    OutlinedButton(
                                        onClick = {
                                            busyPicId = request.id
                                            scope.launch {
                                                try {
                                                    apiClient.rejectKeyAccessRequest(request.id)
                                                    onNotice("Rejected at PIC stage.")
                                                    reload()
                                                } catch (e: Exception) {
                                                    onNotice(e.message ?: "Reject failed.")
                                                } finally {
                                                    busyPicId = null
                                                }
                                            }
                                        },
                                        enabled = busyPicId != request.id,
                                    ) { Text("Reject") }
                                }
                            }
                        }
                    }
                }

                val approved = myRequests.filter { it.status == KeyAccessRequestStatus.APPROVED }
                if (approved.isNotEmpty()) {
                    ApprovedPasskeyPicker(
                        approved = approved,
                        selectedId = selectedApprovedId,
                        menuExpanded = approvedMenuExpanded,
                        onExpandedChange = { approvedMenuExpanded = it },
                        onSelect = { id ->
                            selectedApprovedId = id
                            approvedMenuExpanded = false
                        },
                    )
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("New request", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (exceptionSites.isEmpty()) {
                            Text(
                                "No exception locations available (every active site is already in your standing assignments).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text("Location (outside your assignments)", style = MaterialTheme.typography.labelLarge)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                exceptionSites.forEach { site ->
                                    FilterChip(
                                        selected = site.id == selectedSiteId,
                                        onClick = { selectedSiteId = site.id },
                                        label = { Text(site.name) },
                                    )
                                }
                            }

                            if (selectedSiteId != null) {
                                HorizontalDivider()
                                Text("Key(s) at this location", style = MaterialTheme.typography.labelLarge)
                                when {
                                    loadingKeys -> CircularProgressIndicator()
                                    keysAtSite.isEmpty() -> Text(
                                        "No active keys at this site yet.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    else -> FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        keysAtSite.forEach { key ->
                                            FilterChip(
                                                selected = key.id in selectedKeyIds,
                                                onClick = { toggleKey(key) },
                                                label = { Text(key.displayName) },
                                            )
                                        }
                                    }
                                }

                                if (isVendor) {
                                    HorizontalDivider()
                                    Text("Person In Charge", style = MaterialTheme.typography.labelLarge)
                                    if (sitePics.isEmpty()) {
                                        Text(
                                            "No Technicians assigned to this site yet.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    } else {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            sitePics.forEach { pic ->
                                                FilterChip(
                                                    selected = pic.id == selectedPicId,
                                                    onClick = { selectedPicId = pic.id },
                                                    label = { Text(pic.displayName) },
                                                )
                                            }
                                        }
                                    }
                                    Text("Documents (Work Permit / NIOSH / IC)", style = MaterialTheme.typography.labelLarge)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        listOf("WORK_PERMIT", "NIOSH", "IC").forEach { kind ->
                                            val attached = documents.any { it.docKind == kind }
                                            OutlinedButton(onClick = {
                                                pendingDocKind = kind
                                                docPicker.launch("*/*")
                                            }) {
                                                Text(if (attached) "$kind ✓" else "Attach $kind")
                                            }
                                        }
                                    }
                                }
                            }

                            DateTimePickerField(
                                label = "Pickup",
                                epochMillis = pickupEpochMillis,
                                onEpochMillisChange = { pickupEpochMillis = it },
                            )
                            DateTimePickerField(
                                label = "Return",
                                epochMillis = returnEpochMillis,
                                onEpochMillisChange = { returnEpochMillis = it },
                            )
                            OutlinedTextField(
                                value = reason,
                                onValueChange = { reason = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Reason") },
                                minLines = 2,
                            )

                            Button(
                                onClick = ::submit,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedSiteId != null && selectedKeyIds.isNotEmpty() && !submitting,
                            ) {
                                Text(if (submitting) "Submitting…" else "Submit request")
                            }
                        }
                    }
                }

                Text("My requests", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (myRequests.isEmpty()) {
                    Text(
                        "You haven't submitted any key access requests yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    myRequests.forEach { request ->
                        KeyAccessRequestRow(
                            request = request,
                            busy = busyCancelId == request.id,
                            onCancel = if (isCancellableStatus(request.status)) {
                                { cancel(request.id) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovedPasskeyPicker(
    approved: List<KeyAccessRequestDto>,
    selectedId: String?,
    menuExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    val selected = approved.firstOrNull { it.id == selectedId }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Approved passkeys",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Select an approval to reveal its PIN and which cabinet to use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = onExpandedChange,
            ) {
                OutlinedTextField(
                    value = selected?.let { approvedDropdownLabel(it) } ?: "Select an approved request",
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onExpandedChange(false) },
                ) {
                    approved.forEach { request ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(approvedDropdownLabel(request), fontWeight = FontWeight.SemiBold)
                                    Text(
                                        cabinetSummary(request),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            onClick = { onSelect(request.id) },
                        )
                    }
                }
            }

            if (selected != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f))
                Text(
                    "Location: ${selected.siteName?.takeIf { it.isNotBlank() } ?: selected.siteId}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Cabinet: ${cabinetSummary(selected)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "PIN",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = selected.generatedPasskey ?: "····",
                    style = MaterialTheme.typography.displaySmall.readout(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
                selected.passkeyExpiresAtEpochMillis?.let { expiresAt ->
                    Text(
                        "Enter at that cabinet's Passkey login. Valid until ${formatEpochMillis(expiresAt)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

private fun approvedDropdownLabel(request: KeyAccessRequestDto): String {
    val site = request.siteName?.takeIf { it.isNotBlank() } ?: "Location"
    val reason = request.reason?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    return "$site$reason"
}

private fun cabinetSummary(request: KeyAccessRequestDto): String =
    when {
        request.cabinetNames.isEmpty() -> "No key cabinet registered at this location yet"
        request.cabinetNames.size == 1 -> request.cabinetNames.first()
        else -> request.cabinetNames.joinToString(", ")
    }

@Composable
private fun KeyAccessRequestRow(
    request: KeyAccessRequestDto,
    busy: Boolean = false,
    onCancel: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    request.siteName?.takeIf { it.isNotBlank() } ?: "${request.keyIds.size} key(s)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    request.status.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (request.status) {
                        KeyAccessRequestStatus.APPROVED -> MaterialTheme.colorScheme.tertiary
                        KeyAccessRequestStatus.REJECTED,
                        KeyAccessRequestStatus.REVOKED,
                        KeyAccessRequestStatus.EXPIRED,
                        KeyAccessRequestStatus.CANCELLED,
                        -> MaterialTheme.colorScheme.error
                        KeyAccessRequestStatus.PENDING,
                        KeyAccessRequestStatus.PENDING_PIC,
                        KeyAccessRequestStatus.PENDING_RA,
                        -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (request.cabinetNames.isNotEmpty()) {
                Text(
                    "Cabinet: ${cabinetSummary(request)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${request.keyIds.size} key(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            request.reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            val window = windowLabel(request)
            if (window != null) {
                Text(window, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onCancel != null) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(if (busy) "Cancelling…" else "Cancel request")
                }
            }
        }
    }
}

/** PENDING/PENDING_PIC/PENDING_RA/APPROVED only — matches the backend's own CANCELLABLE_STATUSES
 * in keyAccessRequests.js's POST /:id/cancel; every other status is already terminal. */
internal fun isCancellableStatus(status: KeyAccessRequestStatus): Boolean = when (status) {
    KeyAccessRequestStatus.PENDING,
    KeyAccessRequestStatus.PENDING_PIC,
    KeyAccessRequestStatus.PENDING_RA,
    KeyAccessRequestStatus.APPROVED,
    -> true
    KeyAccessRequestStatus.REJECTED,
    KeyAccessRequestStatus.REVOKED,
    KeyAccessRequestStatus.EXPIRED,
    KeyAccessRequestStatus.CANCELLED,
    -> false
}

/**
 * [KeyAccessRequestDto.returnAtEpochMillis] no longer necessarily reflects only what was
 * originally submitted — an APPROVED request left unused past its window is auto-extended by 1
 * hour, repeating, by the backend's keyAccessAutoExtend.js, which bumps this field alongside the
 * PIN's own expiry. Landed on: relabel "return" to "current return" whenever the request is
 * still APPROVED (the only status auto-extend ever touches) — cheap, always-accurate (it never
 * claims a specific extension happened, just that this is the live value, extended or not), and
 * needs no new "was this extended" field on the DTO. Every other status keeps the original
 * "return" wording, since a non-APPROVED request's return_at_epoch_ms is frozen (auto-extend's
 * own query only ever matches status = 'APPROVED').
 */
internal fun windowLabel(request: KeyAccessRequestDto): String? {
    val pickup = request.pickupAtEpochMillis
    val ret = request.returnAtEpochMillis
    val returnLabel = if (request.status == KeyAccessRequestStatus.APPROVED) "current return" else "return"
    return if (pickup != null && ret != null) {
        "Pickup ${formatEpochMillis(pickup)} → $returnLabel ${formatEpochMillis(ret)}"
    } else {
        "Return within ${formatDuration(request.requestedDurationMinutes)}"
    }
}

internal fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours == 0 -> "${remaining}m"
        remaining == 0 -> "${hours}h"
        else -> "${hours}h ${remaining}m"
    }
}

internal fun formatEpochMillis(epochMillis: Long): String {
    val instant = java.time.Instant.ofEpochMilli(epochMillis)
    val zoned = instant.atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("MMM d, HH:mm").format(zoned)
}
