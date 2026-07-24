package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.ekms.terminal.data.TerminalUser
import com.ekms.terminal.hardware.FingerprintEnrollmentOutcome
import com.ekms.terminal.hardware.FingerprintEnrollmentSummary
import com.ekms.terminal.hardware.FingerprintHardwareState

/**
 * Super Admin-only guided fingerprint enrollment (reached via [openAdmin]'s existing gate, same
 * pattern [CardEnrollmentScreen] already uses — no new gating mechanism). Scope: ENROLLMENT
 * only, matching this session's agreed scope — no runtime fingerprint-based login/matching here.
 *
 * The R503 module auto-assigns and owns the actual template; this screen only ever sees an
 * integer template ID (0-199) reported back after a successful [FingerprintEnrollmentOutcome.Success] —
 * the biometric data itself never reaches this screen, this process, or the backend.
 */
@Composable
fun FingerprintEnrollmentScreen(
    padding: PaddingValues,
    users: List<TerminalUser>,
    notice: String?,
    hardwareState: FingerprintHardwareState,
    onBack: () -> Unit,
    existingEnrollment: (userId: String) -> FingerprintEnrollmentSummary?,
    onEnroll: (
        userId: String,
        onProgress: (String) -> Unit,
        onOutcome: (FingerprintEnrollmentOutcome) -> Unit,
    ) -> Unit,
    onRevoke: (
        userId: String,
        templateId: Int,
        onOutcome: (success: Boolean, message: String) -> Unit,
    ) -> Unit,
) {
    var selectedUserId by remember(users) { mutableStateOf(users.firstOrNull()?.id.orEmpty()) }
    var enrolling by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val selectedUser = users.firstOrNull { it.id == selectedUserId }
    val existing = selectedUser?.let { existingEnrollment(it.id) }

    fun startEnrollment() {
        val user = selectedUser ?: return
        resultMessage = null
        progressText = "Preparing fingerprint sensor…"
        enrolling = true
        onEnroll(
            user.id,
            { step -> progressText = step },
            { outcome ->
                enrolling = false
                progressText = null
                resultMessage = when (outcome) {
                    is FingerprintEnrollmentOutcome.Success ->
                        "Fingerprint enrolled to ${user.displayName} (template ${outcome.templateId})."

                    is FingerprintEnrollmentOutcome.Failed -> outcome.message
                }
            },
        )
    }

    fun revokeSelected() {
        val user = selectedUser ?: return
        val summary = existing
        if (summary == null) {
            resultMessage = "No fingerprint is enrolled to ${user.displayName}."
            return
        }
        resultMessage = null
        enrolling = true
        onRevoke(user.id, summary.templateId) { success, message ->
            enrolling = false
            resultMessage = if (success) {
                "Fingerprint revoked from ${user.displayName}."
            } else {
                message
            }
        }
    }

    TerminalPage(padding) {
        BackButton(onBack = onBack, enabled = !enrolling)
        HeaderCard(
            title = "Fingerprint enrollment",
            description = "Select a person, then place their finger on the sensor when prompted. " +
                "The module collects six scans and stores one template on the sensor itself — " +
                "no fingerprint image or template ever leaves the sensor module.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }
        resultMessage?.let { message -> SuperAdminNoticeCard(message) }
        Text(
            text = hardwareState.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Personnel record", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (users.isEmpty()) {
            Text("Add personnel before enrolling a fingerprint.")
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { selectedUserId = nextUserId(selectedUserId, users) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !enrolling,
                ) {
                    Text(
                        (selectedUser?.let { it.displayName + " · " + it.role.label } ?: "Select personnel") +
                            " · change",
                    )
                }
            }

            Text(
                text = if (existing != null) {
                    "Currently enrolled: template ${existing.templateId}."
                } else {
                    "No fingerprint currently enrolled."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (enrolling) {
            Text(
                text = progressText ?: "Working…",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Button(
                onClick = ::startEnrollment,
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedUser != null,
            ) {
                Text(if (existing != null) "Re-enroll fingerprint" else "Enroll fingerprint")
            }
            OutlinedButton(
                onClick = ::revokeSelected,
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedUser != null && existing != null,
            ) {
                Text("Revoke this record's fingerprint")
            }
        }
    }
}

private fun nextUserId(currentUserId: String, users: List<TerminalUser>): String {
    if (users.isEmpty()) return ""
    val index = users.indexOfFirst { it.id == currentUserId }
    return users[(index + 1 + users.size) % users.size].id
}
