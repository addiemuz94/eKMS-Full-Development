package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.ekms.shared.api.SiteOfficeHoursDto
import com.ekms.shared.api.UpdateSiteOfficeHoursRequest
import com.ekms.terminal.data.TerminalUserRole
import kotlinx.coroutines.launch

/**
 * Phase 7, item 15. Backend routes (`GET`/`PATCH /v1/admin/sites/:id/office-hours`) were built in
 * Phase 1; this is the first terminal-side screen to call them. Super Admin: full edit
 * (open/close time, timezone) for this terminal's own site. Regional Admin: view-only display of
 * the same values — per the matrix, Regional Admin *edits* office hours on web, but at the
 * *terminal* they only ever view, consistent with "the terminal is always view-only for Regional
 * Admin."
 */
@Composable
fun TerminalOfficeHoursScreen(
    padding: PaddingValues,
    role: TerminalUserRole,
    siteId: String,
    onBack: () -> Unit,
    onLoad: suspend (siteId: String) -> SiteOfficeHoursDto,
    onSave: suspend (siteId: String, request: UpdateSiteOfficeHoursRequest) -> SiteOfficeHoursDto,
) {
    val canEdit = role == TerminalUserRole.SUPER_ADMIN
    val scope = rememberCoroutineScope()

    var loading by remember(siteId) { mutableStateOf(true) }
    var loadError by remember(siteId) { mutableStateOf<String?>(null) }
    var current by remember(siteId) { mutableStateOf<SiteOfficeHoursDto?>(null) }
    var openTime by remember(siteId) { mutableStateOf("") }
    var closeTime by remember(siteId) { mutableStateOf("") }
    var timezone by remember(siteId) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(siteId) {
        loading = true
        loadError = null
        try {
            val hours = onLoad(siteId)
            current = hours
            openTime = hours.openTime
            closeTime = hours.closeTime
            timezone = hours.timezone
        } catch (error: Exception) {
            loadError = error.message ?: "Could not load office hours."
        } finally {
            loading = false
        }
    }

    val timeError: (String) -> String? = { value ->
        if (OFFICE_HOURS_TIME_REGEX.matches(value.trim())) null else "Use 24-hour HH:MM:SS, e.g. 08:00:00."
    }
    val openTimeError = timeError(openTime)
    val closeTimeError = timeError(closeTime)

    fun save() {
        val existing = current ?: return
        saving = true
        statusMessage = null
        scope.launch {
            try {
                val updated = onSave(
                    siteId,
                    UpdateSiteOfficeHoursRequest(
                        openTime = openTime.trim(),
                        closeTime = closeTime.trim(),
                        timezone = timezone.trim(),
                        expectedRevision = existing.revision,
                    ),
                )
                current = updated
                openTime = updated.openTime
                closeTime = updated.closeTime
                timezone = updated.timezone
                statusMessage = "Office hours saved."
            } catch (error: Exception) {
                statusMessage = error.message ?: "Could not save office hours."
            } finally {
                saving = false
            }
        }
    }

    TerminalPage(padding) {
        BackButton(onBack, enabled = !saving)
        HeaderCard(
            title = "Office hours",
            description = if (canEdit) {
                "Open/close time and timezone for this terminal's site."
            } else {
                "View-only: open/close time and timezone for this terminal's site. Edit office hours on the web portal."
            },
        )
        statusMessage?.let { message -> SuperAdminNoticeCard(message) }
        loadError?.let { message -> SuperAdminNoticeCard(message) }

        if (loading) {
            Text("Loading office hours…", style = MaterialTheme.typography.bodyMedium)
        } else if (current != null) {
            if (canEdit) {
                // Phase 9C: fields now share the same SoftCard-wrapped-form pattern as, e.g.,
                // HardwareStatusPage's connect fields — a purely visual/spacing consistency
                // change (12dp inter-field spacing was already inherited from TerminalPage's
                // own Column before; this just groups them into one card + adds that spacing
                // explicitly so it survives being one card content block instead of three
                // separate top-level page items).
                SoftCard(contentPadding = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = openTime,
                            onValueChange = { openTime = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Open time (HH:MM:SS)") },
                            singleLine = true,
                            isError = openTimeError != null,
                            supportingText = { Text(openTimeError ?: "24-hour, e.g. 08:00:00.") },
                        )
                        OutlinedTextField(
                            value = closeTime,
                            onValueChange = { closeTime = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Close time (HH:MM:SS)") },
                            singleLine = true,
                            isError = closeTimeError != null,
                            supportingText = { Text(closeTimeError ?: "24-hour, e.g. 17:00:00.") },
                        )
                        OutlinedTextField(
                            value = timezone,
                            onValueChange = { timezone = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Timezone") },
                            singleLine = true,
                            supportingText = { Text("IANA timezone name, e.g. Asia/Kuala_Lumpur.") },
                        )
                    }
                }
                // Phase 9C: IconActionButton(ACCEPT) in place of the old SoftPrimaryButton —
                // a genuine "confirm this pending edit" action, the same case Key Menu's
                // confirm button fit in Phase 9B. There's no separate Cancel action on this
                // screen (only Save + the page-level Back button, which is shared nav, not a
                // modal counterpart), so no CANCEL button was added — inventing one would be a
                // functional change, out of scope for a styling pass.
                IconActionButton(
                    type = ActionButtonType.ACCEPT,
                    label = "Save office hours",
                    onClick = ::save,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving && openTimeError == null && closeTimeError == null && timezone.isNotBlank(),
                )
            } else {
                // Phase 9C audit: already visually distinguishable from edit mode at a glance —
                // bordered/labeled OutlinedTextFields (+ now IconActionButton) above read as an
                // editable form; this branch is plain label/value text with no input chrome at
                // all inside a card, the standard Material read-only pattern. Left unchanged.
                SoftCard(contentPadding = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AdminMenuReadOnlyValue(label = "Open time", value = openTime)
                        AdminMenuReadOnlyValue(label = "Close time", value = closeTime)
                        AdminMenuReadOnlyValue(label = "Timezone", value = timezone)
                    }
                }
            }
        }
    }
}

private val OFFICE_HOURS_TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$")

@Composable
private fun AdminMenuReadOnlyValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "Not set" }, style = MaterialTheme.typography.bodyLarge)
    }
}
