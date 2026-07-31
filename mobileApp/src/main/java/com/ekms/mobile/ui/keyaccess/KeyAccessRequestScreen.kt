package com.ekms.mobile.ui.keyaccess

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.mobile.data.MobileApiClient
import com.ekms.mobile.ui.theme.readout
import com.ekms.shared.api.AuthUserProfile
import com.ekms.shared.api.CreateKeyAccessRequestRequest
import com.ekms.shared.api.KeyAccessRequestDto
import com.ekms.shared.api.KeyAccessRequestStatus
import com.ekms.shared.api.KeyDto
import com.ekms.shared.api.SiteDto
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Only B — exception Key Access Apply. Technician/Vendor pick a site outside standing
 * assignments, key(s) at that site, calendar pickup/return, and a reason. PIN after RA approval
 * is shown only after selecting an approved request from the dropdown (with site + cabinet).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeyAccessRequestScreen(
    apiClient: MobileApiClient,
    profile: AuthUserProfile,
    onNotice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var exceptionSites by remember { mutableStateOf<List<SiteDto>>(emptyList()) }
    var keysAtSite by remember { mutableStateOf<List<KeyDto>>(emptyList()) }
    var myRequests by remember { mutableStateOf<List<KeyAccessRequestDto>>(emptyList()) }
    var selectedSiteId by remember { mutableStateOf<String?>(null) }
    var selectedKeyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reason by remember { mutableStateOf("") }
    var pickupEpochMillis by remember { mutableStateOf(defaultPickupEpochMillis()) }
    var returnEpochMillis by remember { mutableStateOf(defaultReturnEpochMillis()) }
    var submitting by remember { mutableStateOf(false) }
    var loadingKeys by remember { mutableStateOf(false) }
    var selectedApprovedId by remember { mutableStateOf<String?>(null) }
    var approvedMenuExpanded by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        loadError = null
        try {
            exceptionSites = apiClient.listExceptionSites()
            myRequests = apiClient.listKeyAccessRequests(status = "ALL")
            val approvedIds = myRequests
                .filter { it.status == KeyAccessRequestStatus.APPROVED }
                .map { it.id }
                .toSet()
            if (selectedApprovedId !in approvedIds) {
                selectedApprovedId = null
            }
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load exception sites and requests."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(selectedSiteId) {
        val siteId = selectedSiteId
        selectedKeyIds = emptySet()
        keysAtSite = emptyList()
        if (siteId == null) return@LaunchedEffect
        loadingKeys = true
        try {
            keysAtSite = apiClient.listExceptionSiteKeys(siteId)
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
                    ),
                )
                selectedKeyIds = emptySet()
                reason = ""
                onNotice("Request submitted. A Regional Admin will review it.")
                reload()
            } catch (e: Exception) {
                onNotice(e.message ?: "Couldn't submit the request.")
            } finally {
                submitting = false
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
                    myRequests.forEach { request -> KeyAccessRequestRow(request) }
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
private fun KeyAccessRequestRow(request: KeyAccessRequestDto) {
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
                        -> MaterialTheme.colorScheme.error
                        KeyAccessRequestStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
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
        }
    }
}

internal fun windowLabel(request: KeyAccessRequestDto): String? {
    val pickup = request.pickupAtEpochMillis
    val ret = request.returnAtEpochMillis
    return if (pickup != null && ret != null) {
        "Pickup ${formatEpochMillis(pickup)} → return ${formatEpochMillis(ret)}"
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
