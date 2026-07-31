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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Only B — exception Key Access Apply. Technician/Vendor pick a site outside standing
 * assignments, key(s) at that site, calendar pickup/return, and a reason. PIN after RA approval.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    var pickupText by remember { mutableStateOf(defaultPickupText()) }
    var returnText by remember { mutableStateOf(defaultReturnText()) }
    var submitting by remember { mutableStateOf(false) }
    var loadingKeys by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        loadError = null
        try {
            exceptionSites = apiClient.listExceptionSites()
            myRequests = apiClient.listKeyAccessRequests(status = "ALL")
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
        val pickup = parseLocalDateTimeToEpoch(pickupText)
        val returnAt = parseLocalDateTimeToEpoch(returnText)
        if (pickup == null || returnAt == null) {
            onNotice("Use pickup/return as yyyy-MM-dd HH:mm")
            return
        }
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
                approved.forEach { request -> ApprovedPasskeyCard(request) }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

                            OutlinedTextField(
                                value = pickupText,
                                onValueChange = { pickupText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Pickup (yyyy-MM-dd HH:mm)") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = returnText,
                                onValueChange = { returnText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Return (yyyy-MM-dd HH:mm)") },
                                singleLine = true,
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

@Composable
private fun ApprovedPasskeyCard(request: KeyAccessRequestDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Approved — your passkey",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = request.generatedPasskey ?: "····",
                style = MaterialTheme.typography.displaySmall.readout(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
            request.passkeyExpiresAtEpochMillis?.let { expiresAt ->
                Text(
                    "Enter at the terminal Passkey login. Valid until ${formatEpochMillis(expiresAt)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun KeyAccessRequestRow(request: KeyAccessRequestDto) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${request.keyIds.size} key(s)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    request.status.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (request.status) {
                        KeyAccessRequestStatus.APPROVED -> MaterialTheme.colorScheme.tertiary
                        KeyAccessRequestStatus.REJECTED -> MaterialTheme.colorScheme.error
                        KeyAccessRequestStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
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

private val localDateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun defaultPickupText(): String =
    LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0).format(localDateTimeFmt)

private fun defaultReturnText(): String =
    LocalDateTime.now().plusHours(5).withMinute(0).withSecond(0).withNano(0).format(localDateTimeFmt)

private fun parseLocalDateTimeToEpoch(text: String): Long? =
    try {
        LocalDateTime.parse(text.trim(), localDateTimeFmt)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
