package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.terminal.data.CheckoutDeadlineChoice
import com.ekms.terminal.data.CheckoutDeadlinePolicy
import com.ekms.terminal.ui.theme.LocalEkmsColors
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Phase 5. Blocks progression of a take session (single or multi-key) when
 * [CheckoutDeadlinePolicy.computeDeadline] determines an automatic due date isn't safe — either
 * today's office-hours close time is less than [CheckoutDeadlinePolicy.CLOSE_TO_DEADLINE_THRESHOLD_MINUTES]
 * away (or already passed), or the office-hours fetch itself failed. The operator must resolve
 * one of the two paths below before the physical take sequence starts; there is no "cancel" —
 * per this phase's confirmed design, this decision is mandatory, not skippable.
 *
 * **Judgment call, flagged rather than assumed:** the spec asks for "manually enter a return
 * time," which could mean an absolute clock time or a relative duration. This screen uses a
 * 24-hour HH:MM clock-time field (today's date in the site's office-hours timezone, rolling to
 * tomorrow if the entered time has already passed) to match the HH:MM:SS style already used by
 * [TerminalOfficeHoursScreen] elsewhere in this app, rather than inventing a different input
 * style (e.g. "minutes from now"). Revisit if a duration-based picker is the intended UX.
 *
 * Phase 9E (visual/theme only): Manual-entry now gets its own [SoftCard] (title + field +
 * button), matching Emergency's existing card — before this it was a bare field+button floating
 * directly on the page next to Emergency's fully-cased choice, reading as an afterthought next
 * to a considered option rather than two equally deliberate paths. "Use this return time" is now
 * [IconActionButton] ([ActionButtonType.ACCEPT]) — a clean fit, it's the screen's one normal-path
 * confirm action. **"Mark as Emergency" deliberately stays a plain [Button], not
 * `IconActionButton`** — `ACCEPT`'s success/green tone would misrepresent an emergency
 * declaration as a calm, positive confirmation; `IconActionButton` has no
 * warning/emergency-toned type today (only `CANCEL`/`ACCEPT`/`ADD`), and adding one is a
 * component-surface change beyond this pass's visual/theme scope. Instead it's tinted with
 * [LocalEkmsColors]'s existing `warning` token (the same tone `HintSeverity`/`SoftAssistChip`
 * already use) rather than the default primary color — reusing an established tone, not
 * inventing a new one, to make the two paths read as distinct (normal = accept/green, urgent =
 * warning/amber) without forcing a mismatched component.
 */
@Composable
fun TerminalCloseToDeadlineScreen(
    padding: PaddingValues,
    closeAtEpochMillis: Long,
    timezone: String,
    nowEpochMillis: () -> Long,
    onResolved: (CheckoutDeadlineChoice) -> Unit,
) {
    var manualTime by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }

    val zone = remember(timezone) { runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("Asia/Kuala_Lumpur")) }
    val closeTimeLabel = remember(closeAtEpochMillis, zone) {
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(closeAtEpochMillis))
    }

    fun submitManual() {
        val parsedTime = runCatching { LocalTime.parse(manualTime.trim()) }.getOrNull()
        if (parsedTime == null) {
            manualError = "Use 24-hour HH:MM, e.g. 18:30."
            return
        }
        val now = nowEpochMillis()
        var candidate = ZonedDateTime.of(
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate(),
            parsedTime,
            zone,
        )
        // A same-day clock time that's already passed has no sensible same-day interpretation —
        // roll to tomorrow rather than reject outright, so an operator entering e.g. "08:00" at
        // 9pm still gets a valid future deadline without needing to reason about dates.
        if (candidate.toInstant().toEpochMilli() <= now) {
            candidate = candidate.plusDays(1)
        }
        manualError = null
        onResolved(CheckoutDeadlineChoice.manual(candidate.toInstant().toEpochMilli()))
    }

    fun submitEmergency() {
        onResolved(CheckoutDeadlineChoice.emergency(nowEpochMillis()))
    }

    TerminalPage(padding) {
        HeaderCard(
            title = "Return time needed",
            description = "This site's office hours close at $closeTimeLabel today — too soon to " +
                "set an automatic return deadline. Enter a return time or mark this as an emergency checkout.",
        )

        // Phase 9E: Manual-entry now grouped in its own SoftCard, same shape as Emergency's
        // card below — before this it was a bare field+button floating on the page, reading as
        // an afterthought next to Emergency's fully-cased choice.
        SoftCard(contentPadding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Manual return time", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = manualTime,
                    onValueChange = {
                        manualTime = it
                        manualError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Return time (HH:MM)") },
                    singleLine = true,
                    isError = manualError != null,
                    supportingText = { Text(manualError ?: "24-hour, e.g. 18:30. A past time rolls to tomorrow.") },
                )
                IconActionButton(
                    type = ActionButtonType.ACCEPT,
                    label = "Use this return time",
                    onClick = ::submitManual,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = manualTime.isNotBlank(),
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
                // the urgent alternative to Manual-entry's accept-green button above.
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
