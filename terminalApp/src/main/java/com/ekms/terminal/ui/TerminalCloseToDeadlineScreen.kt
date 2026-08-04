package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.terminal.data.CheckoutDeadlineChoice
import com.ekms.terminal.data.CheckoutDeadlinePolicy
import com.ekms.terminal.ui.theme.LocalEkmsColors

/**
 * Mandatory-manual-return-time rework: every Take flow except Only B passkey now unconditionally
 * requires the operator to resolve this screen before the physical take sequence starts — no more
 * office-hours fetch, no Auto fast path, no "too close to close time" conditional. There is no
 * "cancel" here, same as before this rework — the decision is mandatory, not skippable.
 *
 * Manual entry is now the large custom [AnalogTimePicker] (time-only, always due today, rolling
 * to tomorrow if the selected time has already passed — see its own doc) instead of the old
 * `HH:MM` text field. Emergency is unchanged: a flat [CheckoutDeadlinePolicy.EMERGENCY_WINDOW_HOURS]
 * window from now, still its own equally-deliberate [SoftCard], still tinted with the existing
 * `warning` token rather than `IconActionButton`'s `ACCEPT` tone (see below).
 *
 * **"Mark as Emergency" deliberately stays a plain [Button], not `IconActionButton`** —
 * `ACCEPT`'s success/green tone would misrepresent an emergency declaration as a calm, positive
 * confirmation, and `IconActionButton` has no warning/emergency-toned type today (only
 * `CANCEL`/`ACCEPT`/`ADD`). Tinted with [LocalEkmsColors]'s existing `warning` token (the same
 * tone `HintSeverity`/`SoftAssistChip` already use) so the two paths read as distinct (normal =
 * accept/green analog clock, urgent = warning/amber button) without forcing a mismatched
 * component or inventing a new one.
 */
@Composable
fun TerminalCloseToDeadlineScreen(
    padding: PaddingValues,
    nowEpochMillis: () -> Long,
    onResolved: (CheckoutDeadlineChoice) -> Unit,
) {
    fun submitEmergency() {
        onResolved(CheckoutDeadlineChoice.emergency(nowEpochMillis()))
    }

    TerminalPage(padding) {
        HeaderCard(
            title = "Return time needed",
            description = "Set a return time for this checkout, or mark it as an emergency.",
        )

        SoftCard(contentPadding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Return time", fontWeight = FontWeight.SemiBold)
                AnalogTimePicker(
                    nowEpochMillis = nowEpochMillis,
                    onConfirm = { dueAtEpochMillis -> onResolved(CheckoutDeadlineChoice.manual(dueAtEpochMillis)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SoftCard(contentPadding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Emergency checkout", fontWeight = FontWeight.SemiBold)
                Text(
                    "Sets the return deadline to ${CheckoutDeadlinePolicy.EMERGENCY_WINDOW_HOURS} hours " +
                        "from now and flags this checkout as an emergency for follow-up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Deliberately a plain Button, not IconActionButton — see class doc. Tinted
                // with the existing `warning` token (not the default primary) so this reads as
                // the urgent alternative to the analog clock's accept-green confirm button above.
                val colors = LocalEkmsColors.current
                Button(
                    onClick = ::submitEmergency,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.warning,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Mark as Emergency")
                }
            }
        }
    }
}
