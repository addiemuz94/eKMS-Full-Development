package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.terminal.data.CheckoutDeadlineChoice
import com.ekms.terminal.data.CheckoutDeadlinePolicy
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
        SoftPrimaryButton(
            text = "Use this return time",
            onClick = ::submitManual,
            enabled = manualTime.isNotBlank(),
        )

        SoftCard(contentPadding = 16.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Emergency checkout", fontWeight = FontWeight.SemiBold)
                Text(
                    "Sets the return deadline to ${CheckoutDeadlinePolicy.EMERGENCY_WINDOW_HOURS} hours " +
                        "from now and flags this checkout as an emergency for follow-up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = ::submitEmergency, modifier = Modifier.fillMaxWidth()) {
                    Text("Mark as Emergency")
                }
            }
        }
    }
}
