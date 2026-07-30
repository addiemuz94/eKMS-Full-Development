package com.ekms.terminal.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.ekms.terminal.data.TerminalUser
import com.ekms.terminal.data.TerminalUserRole
import com.ekms.terminal.hardware.FingerprintEnrollmentSummary
import com.ekms.terminal.hardware.UidEnrollmentSummary
import com.ekms.terminal.ui.theme.StatusTone

/**
 * List → per-user detail rework: Personnel Management is now the only path to NFC card,
 * fingerprint, and face enrollment (the 3 standalone Admin Menu tiles were removed). This screen
 * shows every locally-known personnel record; tapping one opens [PersonnelDetailScreen].
 */
@Composable
fun PersonnelListScreen(
    padding: PaddingValues,
    users: List<TerminalUser>,
    notice: String?,
    onBack: () -> Unit,
    onAddPersonnel: () -> Unit,
    onOpenDetail: (TerminalUser) -> Unit,
) {
    TerminalPage(padding) {
        BackButton(onBack = onBack)
        HeaderCard(
            title = "Personnel Management",
            description = "Select a person to view their details and enrollment status, or add new personnel.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }

        Button(onClick = onAddPersonnel, modifier = Modifier.fillMaxWidth()) {
            Text("Add personnel")
        }

        if (users.isEmpty()) {
            Text("No personnel enrolled yet.")
        } else {
            users.forEach { user ->
                StatusRingCard(
                    tone = StatusTone.NORMAL,
                    onClick = { onOpenDetail(user) },
                ) {
                    Text(user.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        user.role.label + if (user.isPreset) " · Built-in Super Admin" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * Read-only personnel fields (reuses [AdminMenuReadOnlyField] for visual consistency with the
 * Admin Menu's own field rows) plus the 3 enrollment sub-flows, each scoped to [user] by the
 * caller — this screen never touches the actual Card/Fingerprint/Face enrollment logic itself.
 *
 * "No." / staffId and "Additional number" from the reference vendor-manual screenshot have no
 * equivalent in [TerminalUser] and are deliberately omitted, not invented. Timetable and
 * Multi-Authentication User Group are explicitly out of scope per this rework's brief.
 */
@Composable
fun PersonnelDetailScreen(
    padding: PaddingValues,
    user: TerminalUser,
    notice: String?,
    cardStatus: UidEnrollmentSummary?,
    fingerprintStatus: FingerprintEnrollmentSummary?,
    faceEnrolled: Boolean,
    onBack: () -> Unit,
    onOpenCardEnrollment: () -> Unit,
    onOpenFingerprintEnrollment: () -> Unit,
    onOpenFaceEnrollment: () -> Unit,
) {
    // Vendor may only ever enroll an NFC card (permanent rule — see the same exclusion applied
    // where FingerprintEnrollmentScreen/FaceEnrollmentScreen are entered elsewhere in this app).
    val biometricsAvailable = user.role != TerminalUserRole.VENDOR

    TerminalPage(padding) {
        BackButton(onBack = onBack, label = "Back to Personnel Management")
        HeaderCard(
            title = user.displayName,
            description = "Personnel details. Delete is web-only — this screen has no delete action.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }

        AdminMenuReadOnlyField(label = "Name", value = user.displayName)
        AdminMenuReadOnlyField(label = "Identity / Role", value = user.role.label)
        // Passwords are write-only (CLAUDE.md boundary #7) — a fixed placeholder only, never the
        // real value, and never editable from this read-only screen.
        AdminMenuReadOnlyField(label = "Password", value = "••••••")

        StatusRingCard(tone = StatusTone.NORMAL, onClick = onOpenCardEnrollment) {
            Text("User Card (NFC)", fontWeight = FontWeight.SemiBold)
            Text(
                if (cardStatus != null) "Enrolled" else "Not enrolled",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (biometricsAvailable) {
            StatusRingCard(tone = StatusTone.NORMAL, onClick = onOpenFingerprintEnrollment) {
                Text("Fingerprint entry", fontWeight = FontWeight.SemiBold)
                Text(
                    if (fingerprintStatus != null) "Enrolled" else "Not enrolled",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatusRingCard(tone = StatusTone.NORMAL, onClick = onOpenFaceEnrollment) {
                Text("Face Registration", fontWeight = FontWeight.SemiBold)
                Text(
                    if (faceEnrolled) "Enrolled" else "Not enrolled",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            AdminMenuReadOnlyField(label = "Fingerprint entry", value = "Not available for Vendor role")
            AdminMenuReadOnlyField(label = "Face Registration", value = "Not available for Vendor role")
        }
    }
}
