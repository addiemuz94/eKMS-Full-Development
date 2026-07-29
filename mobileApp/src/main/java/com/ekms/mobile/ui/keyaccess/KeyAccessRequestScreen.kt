package com.ekms.mobile.ui.keyaccess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val DEFAULT_CEILING_MINUTES = 1440
private const val MIN_DURATION_MINUTES = 15
private const val DURATION_STEP_MINUTES = 15

/**
 * Technician/Vendor's Key Access Request screen — the mobile form promised since last session's
 * schema work. Two sections: submit a new request (key picker + duration, bounded by the site's
 * Region ceiling) and a status list of the requester's own past/pending requests, with an
 * APPROVED request's 4-digit passkey made the most visually prominent thing on the whole screen
 * per the task's own instruction — it's the one piece of information the requester came here for.
 *
 * Key scoping mirrors terminalApp's Key Menu exactly (`TerminalAdminApp.authorizedKeysForCurrentUser`):
 * access grants filtered to a currently-valid validFrom/validUntil window, flatMapped to key ids,
 * resolved against the full key list — not a new authorization model. The backend already
 * self-scopes both `GET /access-grants` and `GET /keys` to the caller's own grants/sites, so no
 * client-side userId/siteId filtering is needed beyond the validity-window check.
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
    var availableKeys by remember { mutableStateOf<List<KeyDto>>(emptyList()) }
    var myRequests by remember { mutableStateOf<List<KeyAccessRequestDto>>(emptyList()) }
    var selectedKeyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var durationMinutes by remember { mutableStateOf(DEFAULT_CEILING_MINUTES.toFloat()) }
    var ceilingMinutes by remember { mutableStateOf<Int?>(null) }
    var submitting by remember { mutableStateOf(false) }

    val selectedSiteId = availableKeys.firstOrNull { it.id in selectedKeyIds }?.siteId

    suspend fun reload() {
        loading = true
        loadError = null
        try {
            val grants = apiClient.listMyAccessGrants()
            val keys = apiClient.listKeys()
            val nowMillis = System.currentTimeMillis()
            val authorizedKeyIds = grants
                .asSequence()
                .filter { grant ->
                    val validFrom = grant.validFromEpochMillis
                    val validUntil = grant.validUntilEpochMillis
                    (validFrom == null || nowMillis >= validFrom) && (validUntil == null || nowMillis <= validUntil)
                }
                .flatMap { grant -> grant.keyIds.asSequence() }
                .toSet()
            availableKeys = keys.filter { it.id in authorizedKeyIds }
            myRequests = apiClient.listKeyAccessRequests(status = "ALL")
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load your keys and requests."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(selectedSiteId) {
        val siteId = selectedSiteId
        if (siteId == null) {
            ceilingMinutes = null
            return@LaunchedEffect
        }
        ceilingMinutes = try {
            apiClient.getSiteKeyAccessPolicy(siteId).maxKeyAccessDurationMinutes
        } catch (_: Exception) {
            null
        }
        val effectiveCeiling = (ceilingMinutes ?: DEFAULT_CEILING_MINUTES).toFloat()
        if (durationMinutes > effectiveCeiling) durationMinutes = effectiveCeiling
    }

    fun toggleKey(key: KeyDto) {
        selectedKeyIds = if (key.id in selectedKeyIds) {
            selectedKeyIds - key.id
        } else if (selectedSiteId != null && key.siteId != selectedSiteId) {
            // A request can only cover keys from one site (backend rule) — starting a selection
            // at a different site clears the previous one rather than silently rejecting the tap.
            setOf(key.id)
        } else {
            selectedKeyIds + key.id
        }
    }

    fun submit() {
        val siteId = selectedSiteId ?: return
        submitting = true
        scope.launch {
            try {
                apiClient.createKeyAccessRequest(
                    CreateKeyAccessRequestRequest(
                        siteId = siteId,
                        keyIds = selectedKeyIds,
                        requestedDurationMinutes = durationMinutes.roundToInt(),
                    ),
                )
                selectedKeyIds = emptySet()
                onNotice("Request submitted. A Regional Admin will review it.")
                reload()
            } catch (e: Exception) {
                onNotice(e.message ?: "Couldn't submit the request.")
            } finally {
                submitting = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Key access requests", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        when {
            loading -> CircularProgressIndicator()
            loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
            else -> {
                val approved = myRequests.filter { it.status == KeyAccessRequestStatus.APPROVED }
                approved.forEach { request -> ApprovedPasskeyCard(request) }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("New request", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (availableKeys.isEmpty()) {
                            Text(
                                "You have no keys assigned yet — ask your Regional Admin for an access grant first.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "Select the key(s) you need. All selected keys must be at the same site.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                availableKeys.forEach { key ->
                                    FilterChip(
                                        selected = key.id in selectedKeyIds,
                                        onClick = { toggleKey(key) },
                                        label = { Text(key.displayName) },
                                    )
                                }
                            }

                            HorizontalDivider()

                            val effectiveCeiling = ceilingMinutes ?: DEFAULT_CEILING_MINUTES
                            Text(
                                "Return within: ${formatDuration(durationMinutes.roundToInt())} (up to ${formatDuration(effectiveCeiling)})",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Slider(
                                value = durationMinutes,
                                onValueChange = { durationMinutes = it },
                                valueRange = MIN_DURATION_MINUTES.toFloat()..effectiveCeiling.toFloat(),
                                steps = ((effectiveCeiling - MIN_DURATION_MINUTES) / DURATION_STEP_MINUTES - 1)
                                    .coerceAtLeast(0),
                                enabled = selectedKeyIds.isNotEmpty(),
                            )

                            Button(
                                onClick = ::submit,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedKeyIds.isNotEmpty() && !submitting,
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
    // This is the one thing a requester actually came to this screen for — made deliberately the
    // most visually prominent element (largest text, primary-tinted container, first on screen).
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
                    "Enter this at the terminal's Passkey login. Valid until ${formatEpochMillis(expiresAt)}.",
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
            Text(
                "Requested return within ${formatDuration(request.requestedDurationMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

private fun formatEpochMillis(epochMillis: Long): String {
    val instant = java.time.Instant.ofEpochMilli(epochMillis)
    val zoned = instant.atZone(java.time.ZoneId.systemDefault())
    return java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm").format(zoned)
}
